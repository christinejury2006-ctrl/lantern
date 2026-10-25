package com.lantern.library.shelf3d

import android.content.Context
import android.util.Log
import com.google.android.filament.filamat.MaterialBuilder
import java.io.File

/**
 * The PBR materials for the bookcase, compiled at runtime and cached on disk.
 *
 * Filament has no built-in material, and its offline compiler (`matc`) is a host tool that a
 * Gradle Android build cannot run. `filamat` is the same compiler shipped as a library, so the
 * shader is compiled on the device the first time and the resulting package is cached in the
 * app's files directory. Every launch after that just reads the blob.
 *
 * Compiling takes a few hundred milliseconds, so [load] is meant to be called from a
 * background coroutine before the engine is created; the engine then builds a Material from
 * the bytes instantly.
 */
object ShelfMaterials {

    private const val TAG = "ShelfMaterials"
    /**
     * Bumped whenever the shader changes.
     *
     * The compiled package is cached in filesDir, so a device that ran an older build would
     * otherwise keep loading a material that has no spine sampler in it.
     */
    private const val VERSION = 4

    /** A book: leather or cloth, with a paper fore-edge. */
    private const val BOOK = "book_pbr"

    /** The wooden carcass of the drum. */
    private const val WOOD = "wood_pbr"

    /**
     * A book: its binding colour, and the rectangle of the spine atlas holding its cover art.
     *
     * Every book shares this one material and differs only by instance parameters, so a whole
     * shelf is still one material, one texture and one draw state. The mesh's own uv is
     * remapped through a per-book offset and scale to pick that book's tile.
     *
     * `prepareMaterial(material)` must come first: it fills in the defaults Filament expects
     * before the shader touches any of the standard fields.
     */
    /**
     * The book shader.
     *
     * `MaterialInputs` is **write only**: every field must be computed into a local and assigned
     * once, at the end. Reading `material.baseColor` back to blend something into it looks like
     * ordinary GLSL and is a compile error in matc, which shows up as "book_pbr failed to
     * compile" and, because a material that fails to compile takes the scene with it, as no
     * shelf at all.
     *
     * [ribbon] adds the brass band that marks a book picked for the reader. It is a variant
     * rather than a branch because a compile that fails must never be able to leave the case
     * empty: [compile] falls back to the plain variant.
     */
    private fun bookFragment(ribbon: Boolean): String = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);

            vec2 spineUV = getUV0();
            vec2 atlasUV = materialParams.uvOffset + materialParams.uvScale * spineUV;
            // Binding colour first, then the cover art over it when this book has any.
            vec4 colour = mix(
                vec4(materialParams.baseColor, 1.0),
                texture(materialParams_spineMap, atlasUV),
                materialParams.useArtwork
            );
            float roughness = materialParams.roughness;
            float metallic = materialParams.metallic;

            @RIBBON@

            material.baseColor = colour;
            material.roughness = roughness;
            material.metallic = metallic;
        }
    """.trimIndent().let { template ->
        val band = if (!ribbon) "" else """
            |
            |            // A brass ribbon across the spine of a book picked for the reader. Drawn rather
            |            // than modelled: it is a stripe on the leather, and a second piece of geometry
            |            // per book would cost a draw call to say the same thing.
            |            //
            |            // The band is placed on the spine's own uv, which runs down the book's height, so
            |            // it sits at a fixed height on the book however big the book is on screen. It is
            |            // narrow, and sits just above the middle, where a real ribbon is tied.
            |            float band = step(0.42, spineUV.y) * step(spineUV.y, 0.56) * materialParams.useRibbon;
            |            colour = mix(colour, vec4(0.60, 0.40, 0.13, 1.0), band);
            |            roughness = mix(roughness, 0.30, band);
            |            metallic = mix(metallic, 0.80, band);
        """.trimMargin()
        // The placeholder sits alone on its line; drop the whole line when there is no ribbon,
        // so the plain variant has no dangling marker for matc to choke on.
        template.replace(Regex("(?m)^[ \\t]*@RIBBON@[ \\t]*$"), band)
    }

    /** The woodwork: colour and roughness only, no texture. */
    private val WOOD_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor.rgb = materialParams.baseColor;
            material.roughness = materialParams.roughness;
            material.metallic = materialParams.metallic;
        }
    """.trimIndent()

    /** Bytes for each material, keyed by name. Compiles only what is missing. */
    fun load(context: Context): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>(2)
        out[BOOK] = loadOrCompile(context, BOOK)
        out[WOOD] = loadOrCompile(context, WOOD)
        return out
    }

    const val BOOK_MATERIAL = BOOK
    const val WOOD_MATERIAL = WOOD

    private fun loadOrCompile(context: Context, name: String): ByteArray {
        val file = File(context.filesDir, "${name}_v$VERSION.filamat")
        if (file.isFile && file.length() > 0L) {
            runCatching { file.readBytes() }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }
        val bytes = compile(name)
        runCatching {
            val tmp = File(context.filesDir, "${name}_v$VERSION.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not cache $name", it) }
        return bytes
    }

    /** filamat's native compiler context is process-wide; initialise it exactly once. */
    @Volatile
    private var filamatReady = false

    /**
     * Compiles a material, and never throws because of a shader mistake in the ribbon.
     *
     * A material that fails to compile takes the entire scene with it — the case simply does not
     * appear. The ribbon is a marking, not the shelf, so if its variant will not compile the
     * plain book shader is used and the shelf still works: a missing ribbon is a much smaller
     * problem than a missing library.
     */
    private fun compile(name: String): ByteArray {
        if (name == BOOK) {
            build(name, bookFragment(ribbon = true), withArtwork = true)?.let { return it }
            Log.w(TAG, "the ribbon variant of $name did not compile; spines will be plain")
            build(name, bookFragment(ribbon = false), withArtwork = true)?.let { return it }
            error("$name failed to compile")
        }
        return build(name, WOOD_FRAGMENT, withArtwork = false)
            ?: error("$name failed to compile")
    }

    /** Compiles one variant, returning null for a package matc rejected. */
    private fun build(name: String, fragment: String, withArtwork: Boolean): ByteArray? {
        // Must run before any MaterialBuilder use. Deliberately never shut down: the context is
        // a small static allocation, and tearing it down here could free it from under a
        // concurrent compile.
        synchronized(this) {
            if (!filamatReady) {
                MaterialBuilder.init()
                filamatReady = true
            }
        }

        val builder = MaterialBuilder()
            // MOBILE generates GLES and Vulkan shaders, so the package works whichever
            // backend the driver gives us.
            .platform(MaterialBuilder.Platform.MOBILE)
            .shading(MaterialBuilder.Shading.LIT)
            .name(name)
            // NONE keeps compilation quick; this shader has nothing to optimise away anyway.
            .optimization(MaterialBuilder.Optimization.NONE)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "baseColor")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "metallic")
            // Winding must never be able to make a book disappear.
            .culling(MaterialBuilder.CullingMode.NONE)
            // UVs are authored top-down, like every image this app draws. The spine atlas
            // relies on it: v = 0 is the top of a book and the top of its tile.
            .flipUV(false)
            .apply {
                if (withArtwork) {
                    uniformParameter(MaterialBuilder.UniformType.FLOAT2, "uvOffset")
                    uniformParameter(MaterialBuilder.UniformType.FLOAT2, "uvScale")
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, "useArtwork")
                    // 1 when this book is a pick for the reader, so the spine carries a ribbon.
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, "useRibbon")
                    samplerParameter(
                        MaterialBuilder.SamplerType.SAMPLER_2D,
                        MaterialBuilder.SamplerFormat.FLOAT,
                        MaterialBuilder.ParameterPrecision.DEFAULT,
                        "spineMap"
                    )
                    material(fragment)
                } else {
                    material(WOOD_FRAGMENT)
                }
            }

        // build() returns a MaterialPackage: the native blob plus an isValid flag. Always check
        // the flag — an invalid package handed to Material.Builder produces an engine
        // assertion that says nothing about the shader that actually failed to compile.
        val pkg = builder.build()
        // build() returns a MaterialPackage: the native blob plus an isValid flag. Always check
        // the flag — an invalid package handed to Material.Builder produces an engine assertion
        // that says nothing about the shader that actually failed to compile.
        if (!pkg.isValid) return null
        val buffer = pkg.buffer
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
