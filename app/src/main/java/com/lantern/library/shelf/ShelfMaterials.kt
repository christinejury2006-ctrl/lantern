package com.lantern.library.shelf

import android.util.Log
import com.google.android.filament.filamat.MaterialBuilder
import java.io.File

/**
 * The one material the whole bookcase is drawn with.
 *
 * Filament has no built-in material: every surface needs a compiled material package, and the
 * `matc` compiler that normally produces one is a host tool this project has no way to run.
 * So the package is compiled on the phone once by filamat, then cached in the app's files
 * directory: the first run pays a few hundred milliseconds on a background thread, and every
 * launch after that just reads the blob.
 *
 * The shader itself is deliberately tiny. It is unlit: the artwork is painted (the room is a
 * photograph of a painted room), and each face carries its own shade in the vertex colour, so
 * there is nothing here that can come out black on a device with no lighting support.
 */
object ShelfMaterials {

    private const val TAG = "ShelfMaterials"
    private const val BLOB = "shelf_art_v1.filamat"

    /**
     * The fragment function: sample the art through a per-book window into the atlas and
     * multiply by the vertex shade.
     *
     * flipUV is off (see [compile]) so uv0 is used exactly as authored, with v = 0 at the top
     * of the image. That is what lets the atlas be drawn top-down with ordinary Canvas code.
     */
    private val FRAGMENT = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            vec4 rect = materialParams.uvRect;
            vec2 uv = getUV0() * rect.zw + rect.xy;
            vec4 texel = texture(materialParams_art, uv);
            material.baseColor = vec4(texel.rgb * getColor().rgb, 1.0);
        }
    """.trimIndent()

    /**
     * Returns the compiled material package, compiling and caching it if needed.
     * Safe to call off the main thread; that is where it is called from.
     */
    fun load(context: android.content.Context): ByteArray {
        val file = File(context.filesDir, BLOB)
        if (file.isFile && file.length() > 0L) {
            val cached = runCatching { file.readBytes() }.getOrNull()
            if (cached != null && cached.isNotEmpty()) return cached
        }
        val bytes = compile()
        runCatching {
            val tmp = File(context.filesDir, "$BLOB.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not cache the shelf material", it) }
        return bytes
    }

    private fun compile(): ByteArray {
        // init() must run before any MaterialBuilder use, once per process.
        MaterialBuilder.init()
        try {
            val pkg = MaterialBuilder()
                // MOBILE generates shaders for both GLES and Vulkan, so the package works
                // whichever backend the engine ends up on.
                .platform(MaterialBuilder.Platform.MOBILE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .name("shelf_art")
                // Winding must not matter: a box drawn with the wrong face order would
                // vanish rather than look slightly off, and that is not a risk worth taking.
                .culling(MaterialBuilder.CullingMode.NONE)
                // Faithful uv0, so the atlas can be authored top-down.
                .flipUV(false)
                // NONE keeps compilation quick and works with the lite filamat variant.
                .optimization(MaterialBuilder.Optimization.NONE)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "uvRect")
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.MEDIUM,
                    "art"
                )
                .require(MaterialBuilder.VertexAttribute.UV0)
                .require(MaterialBuilder.VertexAttribute.COLOR)
                .material(FRAGMENT)
                .build()
            if (!pkg.isValid) {
                throw IllegalStateException("the shelf material failed to compile")
            }
            val buffer = pkg.buffer
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            return out
        } finally {
            MaterialBuilder.shutdown()
        }
    }
}
