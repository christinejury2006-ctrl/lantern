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
    private const val VERSION = 1

    /** A book: leather or cloth, with a paper fore-edge. */
    private const val BOOK = "book_pbr"

    /** The wooden carcass of the drum. */
    private const val WOOD = "wood_pbr"

    /**
     * Every book shares one material and differs only by instance parameters. `baseColor`,
     * `roughness` and `metallic` are all per-instance, so one draw state covers the shelf.
     *
     * `prepareMaterial(material)` must come first: it fills in the defaults Filament expects
     * before the shader touches any of the standard fields.
     */
    private val LIT_FRAGMENT = """
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
        out[BOOK] = loadOrCompile(context, BOOK, roughness = 0.62f, metallic = 0f)
        out[WOOD] = loadOrCompile(context, WOOD, roughness = 0.38f, metallic = 0f)
        return out
    }

    const val BOOK_MATERIAL = BOOK
    const val WOOD_MATERIAL = WOOD

    private fun loadOrCompile(
        context: Context,
        name: String,
        roughness: Float,
        metallic: Float
    ): ByteArray {
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

    private fun compile(name: String): ByteArray {
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
            // UVs are authored top-down, like every image this app draws.
            .flipUV(false)
            .material(LIT_FRAGMENT)

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
