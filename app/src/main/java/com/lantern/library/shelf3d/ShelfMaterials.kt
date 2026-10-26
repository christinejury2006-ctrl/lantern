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
    private const val VERSION = 6

    /** A book: leather or cloth, with a paper fore-edge. */
    private const val BOOK = "book_pbr"

    /** The wooden carcass of the drum. */
    private const val WOOD = "wood_pbr"

    /** The warm panel that lights the recommendation alcove. Unlit: it is a light, not a surface. */
    private const val LAMP = "lamp_unlit"

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
     * This is deliberately the plainest version that draws a spine correctly: assign each
     * MaterialInputs field once and never read one back. `MaterialInputs` is write-only in
     * Filament, and reading `material.baseColor` to blend something into it is a compile error
     * that takes the whole scene down with it.
     *
     * The brass ribbon that marks a recommended book used to live here. It does not any more:
     * it is painted into the spine atlas instead, where the worst case is a wrong pixel rather
     * than a shelf that will not start.
     */
    private val BOOK_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 uv = materialParams.uvOffset + materialParams.uvScale * getUV0();
            vec4 art = texture(materialParams_spineMap, uv);
            // A book with no cover keeps its binding colour.
            material.baseColor = mix(vec4(materialParams.baseColor, 1.0), art, materialParams.useArtwork);
            material.roughness = materialParams.roughness;
            material.metallic = materialParams.metallic;
        }
    """.trimIndent()

    /** The lamp: one flat warm colour, unaffected by the room. */
    private val LAMP_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = vec4(materialParams.baseColor, 1.0);
        }
    """.trimIndent()

    /**
     * The woodwork: painted grain, tinted per part by the instance's base colour.
     *
     * This must stay identical to materials/wood_pbr.mat. The APK normally ships the matc-built
     * version, and a fallback that declares different parameters is worse than no fallback: the
     * scene sets `grainMap` and `uvScale` on every wooden part, and setting a parameter the
     * material does not declare is not a warning Filament forgives.
     */
    private val WOOD_FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec2 uv = getUV0() * materialParams.uvScale;
            vec4 grain = texture(materialParams_grainMap, uv);
            material.baseColor = vec4(materialParams.baseColor, 1.0) * grain;
            material.roughness = materialParams.roughness;
            material.metallic = materialParams.metallic;
        }
    """.trimIndent()

    /**
     * Bytes for each material, keyed by name.
     *
     * The APK ships a material already compiled by matc on the build machine (see
     * the .mat files in materials/ and the workflow that compiles them). That is the normal path and it
     * costs one file read: no compiler on the phone, no hundred-millisecond wait on first
     * launch, and no way for a device's driver to disagree with a shader.
     *
     * [loadOrCompile] remains for builds made without that step, and for anyone running the
     * app from Android Studio.
     */
    fun load(context: Context): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>(2)
        for (name in listOf(BOOK, WOOD, LAMP)) {
            out[name] = loadAsset(context, name) ?: fallBackToCompiling(context, name)
        }
        return out
    }

    /** The precompiled material from the APK's assets, or null when the build did not ship one. */
    private fun loadAsset(context: Context, name: String): ByteArray? =
        runCatching {
            context.assets.open("materials/$name.filamat").use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * The last resort when the APK carries no compiled material.
     *
     * It exists so the app still runs from Android Studio, where the materials step does not
     * run. It is not a normal path: if it is taken, the build shipped the wrong file name or no
     * file at all, and saying so is the difference between a five-minute fix and a shelf that
     * quietly refuses to start.
     */
    private fun fallBackToCompiling(context: Context, name: String): ByteArray {
        Log.w(TAG, "no precompiled materials/$name.filamat in the APK; compiling on the device")
        return runCatching { loadOrCompile(context, name) }.getOrElse { error ->
            throw IllegalStateException(
                "The APK has no materials/$name.filamat, and compiling it on the device " +
                    "failed too (${error.message ?: error.javaClass.simpleName}).",
                error
            )
        }
    }

    const val BOOK_MATERIAL = BOOK
    const val WOOD_MATERIAL = WOOD
    const val LAMP_MATERIAL = LAMP

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
     * Compiles a material.
     *
     * A material that fails to compile takes the entire scene with it, so the message names the
     * material: "book_pbr failed to compile" says which shader to look at, where a bare
     * IllegalStateException says nothing.
     */
    private fun compile(name: String): ByteArray = when (name) {
        BOOK -> build(name, BOOK_FRAGMENT, withArtwork = true)
        LAMP -> build(name, LAMP_FRAGMENT, withArtwork = false)
        else -> build(name, WOOD_FRAGMENT, withArtwork = false)
    } ?: error("$name failed to compile")

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
            // The lamp is a light, not a surface: unlit, so the room's lighting cannot dim it.
            .shading(if (name == LAMP) MaterialBuilder.Shading.UNLIT else MaterialBuilder.Shading.LIT)
            .name(name)
            // NONE keeps compilation quick; this shader has nothing to optimise away anyway.
            .optimization(MaterialBuilder.Optimization.NONE)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "baseColor")
            .apply {
                // The lamp is unlit: declaring roughness or metallic would make matc reject it.
                if (name != LAMP) {
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
                    uniformParameter(MaterialBuilder.UniformType.FLOAT, "metallic")
                }
            }
            // Winding must never be able to make a book disappear.
            .culling(MaterialBuilder.CullingMode.NONE)
            // UVs are authored top-down, like every image this app draws. The spine atlas
            // relies on it: v = 0 is the top of a book and the top of its tile.
            .flipUV(false)
            .apply {
                when {
                    withArtwork -> {
                        uniformParameter(MaterialBuilder.UniformType.FLOAT2, "uvOffset")
                        uniformParameter(MaterialBuilder.UniformType.FLOAT2, "uvScale")
                        uniformParameter(MaterialBuilder.UniformType.FLOAT, "useArtwork")
                        samplerParameter(
                            MaterialBuilder.SamplerType.SAMPLER_2D,
                            MaterialBuilder.SamplerFormat.FLOAT,
                            MaterialBuilder.ParameterPrecision.DEFAULT,
                            "spineMap"
                        )
                        material(fragment)
                    }
                    name == WOOD -> {
                        // The grain tiles: a board is metres long and the texture is not.
                        uniformParameter(MaterialBuilder.UniformType.FLOAT2, "uvScale")
                        samplerParameter(
                            MaterialBuilder.SamplerType.SAMPLER_2D,
                            MaterialBuilder.SamplerFormat.FLOAT,
                            MaterialBuilder.ParameterPrecision.DEFAULT,
                            "grainMap"
                        )
                        material(WOOD_FRAGMENT)
                    }
                    else -> material(fragment)
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
