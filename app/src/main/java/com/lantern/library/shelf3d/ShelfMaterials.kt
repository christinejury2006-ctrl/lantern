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
    private const val VERSION = 3

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
    private val BOOK_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 spine = getUV0();
            vec2 uv = materialParams.uvOffset + materialParams.uvScale * spine;
            vec4 art = texture(materialParams_spineMap, uv);
            // A book with no cover keeps its binding colour.
            material.baseColor = mix(vec4(materialParams.baseColor, 1.0), art, materialParams.useArtwork);
            material.roughness = materialParams.roughness;
            material.metallic = materialParams.metallic;

            // A brass ribbon across the spine of a book picked for the reader. Drawn rather than
            // modelled: it is a stripe on the leather, and a second piece of geometry per book
            // would cost draw calls to say the same thing.
            //
            // The band is placed on the spine's own uv, which runs down the book's height, so it
            // sits at a fixed height on the book however tall the book is on screen. It is
            // deliberately narrow and sits just above the middle, where a real ribbon is tied.
            float across = step(0.42, spine.y) * step(spine.y, 0.56) * materialParams.useRibbon;
            vec3 brass = vec3(0.60, 0.40, 0.13);
            material.baseColor = mix(material.baseColor, vec4(brass, 1.0), across);
            material.roughness = mix(material.roughness, 0.30, across);
            material.metallic = mix(material.metallic, 0.80, across);
        }
    """.trimIndent()

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

    private fun compile(name: String): ByteArray = compile(name, name == BOOK)

    private fun compile(name: String, withArtwork: Boolean): ByteArray {
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
                    material(BOOK_FRAGMENT)
                } else {
                    material(WOOD_FRAGMENT)
                }
            }

        // build() returns a MaterialPackage: the native blob plus an isValid flag. Always check
        // the flag — an invalid package handed to Material.Builder produces an engine
        // assertion that says nothing about the shader that actually failed to compile.
        val pkg = builder.build()
        check(pkg.isValid) { "$name failed to compile" }
        val buffer = pkg.buffer
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
