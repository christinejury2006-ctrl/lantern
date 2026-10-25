package com.lantern.library.shelf3d

import android.graphics.Color
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bookcase scene: books standing around a drum, a wooden plinth, a sun and a soft ambient
 * fill, all driven by [CylinderRotationController].
 *
 * Lighting is deliberately two-part. A single directional light gives shape — the falloff
 * across a curved drum is what reads as curvature — and a first-order spherical-harmonic
 * indirect light stops the unlit side going to pure black, which is what makes a lit PBR scene
 * look like a flat silhouette in a dark room. Neither needs a cubemap, so there are no
 * .ktx assets to ship or generate.
 */
class CylinderShelfScene(
    private val bookCount: () -> Int,
    private val controller: CylinderRotationController,
    private val materialBytes: Map<String, ByteArray>,
    /** Colours for each book, supplied by the app so spines match the library's covers. */
    private val bookColors: List<Int>,
    /** Cover art for the spines, or null to draw plain bindings. */
    private val spineAtlas: SpineAtlas? = null
) : FilamentShelfEngine.ShelfContent {



    private var engine: Engine? = null
    private var resources: ShelfResources? = null

    private val bookEntities = ArrayList<Int>(64)
    private val bookInstances = ArrayList<MaterialInstance>(64)
    private val transforms = ArrayList<Int>(64)

    /** Reused every frame so the render loop allocates nothing. */
    private val matrix = FloatArray(16)

    /** Lighting constants, in Filament's physical units (lux). */
    private val sunDirection = floatArrayOf(-0.35f, -0.55f, -0.76f)
    private val sunColor = floatArrayOf(1.0f, 0.94f, 0.86f)
    private var frameNanos = 0L

    var onSceneError: ((String) -> Unit)? = null

    /**
     * Told which book has come to the front whenever that changes.
     *
     * Including while the drum is coasting and settling: a flick that lands on a different book
     * must update the caption, and the DragGestureHandler has no idea where the drum will stop.
     */
    var onFrontBookChanged: ((Int) -> Unit)? = null

    private var lastFrontIndex = -1

    /** Half the horizontal field of view, recomputed whenever the viewport changes shape. */
    private var halfFovH: Float = CylinderMath.halfFovH(aspect = 0.5f)

    /**
     * The drum this scene was built on.
     *
     * Grown from the size of the library so books never overlap, and remembered here because
     * every book position, the carcass, the drag gain and the hit test all have to agree on it.
     */
    private var radius: Float = CylinderMath.DRUM_RADIUS
    private var axis: Float = CylinderMath.AXIS_DISTANCE

    private companion object {
        const val TAG = "CylinderShelfScene"

        /** Just below the board the books stand on: the room's floor. */
        val FLOOR_Y = CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f - 0.035f

        /** Base colours for the room's woodwork, as linear values rather than sRGB. */
        val WOOD_WALL = floatArrayOf(0.20f, 0.115f, 0.070f)
        val WOOD_SHELF = floatArrayOf(0.26f, 0.150f, 0.090f)
        val WOOD_FLOOR = floatArrayOf(0.16f, 0.093f, 0.058f)
    }

    override fun onCreate(engine: Engine, scene: Scene) {
        this.engine = engine
        val res = ShelfResources(engine)
        resources = res

        // The drum's size comes first: everything below is built around it.
        val count = bookCount()
        radius = CylinderMath.radiusFor(count)
        axis = CylinderMath.axisForLibrary(count)

        try {
            val boxMesh = ShelfGeometry.box()
            val vertices = vertexBuffer(engine, boxMesh)
            val indices = indexBuffer(engine, boxMesh)
            res.track(vertices)
            res.track(indices)

            val bookMaterial = buildMaterial(engine, ShelfMaterials.BOOK_MATERIAL)
            res.track(bookMaterial)
            val woodMaterial = buildMaterial(engine, ShelfMaterials.WOOD_MATERIAL)
            res.track(woodMaterial)

            // The carcass wall stands just behind the backs of the books.
            val wallRadius = radius - CylinderMath.BOOK_DEPTH / 2f - 0.008f

            // The board the books stand on. Its inner edge stops at the wall so the two meet
            // cleanly instead of intersecting.
            val plinthMesh = ShelfGeometry.plinth(
                radius = radius + 0.006f,
                topY = CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f,
                thickness = radius + 0.006f - wallRadius,
                axisDistance = axis
            )
            addStatic(
                engine, scene, res, plinthMesh, woodMaterial,
                center = floatArrayOf(0f, CylinderMath.BOOK_Y, -axis),
                halfExtent = floatArrayOf(radius + 0.1f, 0.3f, radius + 0.1f),
                color = WOOD_SHELF
            )

            // The wall behind the books.
            //
            // Two jobs. It hides the books that have turned past the drum's horizon, so the
            // shelf reads as solid instead of as a ring of floating boxes. And it is tall
            // enough to fill the frame: a room-scale backdrop, floor to well above the reader's
            // eye, is the difference between a shelf and a strip of furniture in a black void.
            val wallMesh = ShelfGeometry.backboard(
                radius = wallRadius,
                yBottom = FLOOR_Y - 0.4f,
                yTop = 1.40f,
                segments = 96,
                axisDistance = axis
            )
            addStatic(
                engine, scene, res, wallMesh, woodMaterial,
                center = floatArrayOf(0f, (1.40f + FLOOR_Y - 0.4f) / 2f, -axis),
                halfExtent = floatArrayOf(wallRadius + 0.1f, 1.2f, wallRadius + 0.1f),
                color = WOOD_WALL
            )

            // A floor, so the bottom of the frame is a room rather than a void. Only the strip
            // between the reader and the drum is visible; the rest is behind the wall.
            // The floor reaches past the drum so that no edge of it is ever in shot, however
            // large the library forces the drum to be.
            val floorRadius = radius + 1.6f
            val floorMesh = ShelfGeometry.floor(
                centreZ = -axis,
                y = FLOOR_Y,
                radius = floorRadius
            )
            addStatic(
                engine, scene, res, floorMesh, woodMaterial,
                center = floatArrayOf(0f, FLOOR_Y, -axis),
                halfExtent = floatArrayOf(floorRadius, 0.05f, floorRadius),
                color = WOOD_FLOOR
            )

            // Spine artwork. A single 1x1 texture stands in when there is no atlas, because
            // the material declares a sampler and every instance must bind something to it.
            val artwork = uploadAtlas(engine, spineAtlas)
            res.track(artwork)
            addBooks(engine, scene, res, vertices, indices, bookMaterial, artwork)
            addLights(engine, scene, res)
        } catch (t: Throwable) {
            Log.e(TAG, "could not build the scene", t)
            onSceneError?.invoke(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Keeps material payload and mesh buffers alive until the engine has copied them. */
    private val pendingBuffers = ArrayList<ByteBuffer>(8)

    /**
     * Builds a Material from a compiled package.
     *
     * The payload buffer must stay reachable until Filament has copied it, so it is held here;
     * letting it be collected immediately is a classic intermittent crash.
     */
    private fun buildMaterial(engine: Engine, name: String): Material {
        val bytes = materialBytes[name] ?: error("no compiled material called $name")
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        pendingBuffers.add(buffer)
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    /**
     * Adds a static, full-circle piece of the carcass (the board the books stand on, or the wall
     * behind them).
     *
     * [center] and [halfExtent] describe a box enclosing the whole piece. They are not optional:
     * frustum culling is on by default, and a wrong box makes the piece vanish.
     */
    private fun addStatic(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        mesh: ShelfGeometry.Mesh,
        material: Material,
        center: FloatArray,
        halfExtent: FloatArray,
        color: FloatArray
    ) {
        val instance = material.createInstance().also {
            it.setParameter("baseColor", color[0], color[1], color[2])
            it.setParameter("roughness", 0.62f)
            it.setParameter("metallic", 0.0f)
        }
        res.track(instance)
        val vb = res.track(vertexBuffer(engine, mesh))
        val ib = res.track(indexBuffer(engine, mesh))
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, instance)
            .boundingBox(Box(center.copyOf(), halfExtent.copyOf()))
            .build(engine, entity)
        scene.addEntity(entity)
        staticEntities.add(entity)
    }

    private val staticEntities = ArrayList<Int>(4)

    /**
     * Uploads the spine atlas, or a single white pixel when there is no artwork.
     *
     * SRGB8_A8 rather than RGBA8 because the atlas holds sRGB cover art: the GPU then converts
     * to linear on sample, which is what keeps leather looking like leather under the sun
     * instead of washing out.
     */
    private fun uploadAtlas(engine: Engine, atlas: SpineAtlas?): Texture {
        val source: android.graphics.Bitmap = atlas?.bitmap ?: android.graphics.Bitmap
            .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.WHITE) }

        val texture = Texture.Builder()
            .width(source.width)
            .height(source.height)
            // One level: no mipmaps means no need for the GEN_MIPMAPPABLE usage flag.
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)

        val bytes = ByteBuffer.allocateDirect(source.width * source.height * 4)
            .order(ByteOrder.nativeOrder())
        source.copyPixelsToBuffer(bytes)
        bytes.flip()
        pendingBuffers.add(bytes)
        texture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(bytes, Texture.Format.RGBA, Texture.Type.UBYTE)
        )
        return texture
    }

    private fun addBooks(
        engine: Engine,
        scene: Scene,
        res: ShelfResources,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        material: Material,
        artwork: Texture
    ) {
        val count = bookCount()
        for (i in 0 until count) {
            val instance = material.createInstance()
            val color = bookColors.getOrElse(i) { 0xFF6B4A32.toInt() }
            // Linear-ish base colour; sRGB would need a colour space conversion, and a slightly
            // desaturated leather reads better under the sun anyway.
            instance.setParameter(
                "baseColor",
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f
            )
            instance.setParameter("roughness", 0.55f + (i % 5) * 0.06f)
            instance.setParameter("metallic", 0f)

            // Bind the atlas on every book, then point this one at its own tile.
            instance.setParameter(
                "spineMap",
                artwork,
                TextureSampler(TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            )
            val tile = spineAtlas?.tiles?.getOrNull(i)
            if (tile != null) {
                instance.setParameter("uvOffset", tile.u0, tile.v0)
                instance.setParameter("uvScale", tile.u1 - tile.u0, tile.v1 - tile.v0)
                instance.setParameter("useArtwork", 1f)
            } else {
                instance.setParameter("uvOffset", 0f, 0f)
                instance.setParameter("uvScale", 1f, 1f)
                instance.setParameter("useArtwork", 0f)
            }
            res.track(instance)
            bookInstances.add(instance)

            val entity = EntityManager.get().create()
            val half = floatArrayOf(
                CylinderMath.BOOK_WIDTH / 2f,
                CylinderMath.BOOK_HEIGHT / 2f,
                CylinderMath.BOOK_DEPTH / 2f
            )
            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
                .material(0, instance)
                .boundingBox(Box(floatArrayOf(0f, 0f, 0f), half))
                // Small, always-on-screen objects: culling would cost more than it saves.
                .culling(false)
                .build(engine, entity)
            scene.addEntity(entity)
            bookEntities.add(entity)
            transforms.add(engine.transformManager.getInstance(entity))
        }
        // Place them immediately, so the first frame is already a shelf and not a pile at the
        // origin.
        applyRotation()
    }

    private fun addLights(engine: Engine, scene: Scene, res: ShelfResources) {
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(sunDirection[0], sunDirection[1], sunDirection[2])
            .color(sunColor[0], sunColor[1], sunColor[2])
            .intensity(90_000f)
            // Shadows are Step 4; a shadow map over a spinning drum needs its own tuning.
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
        sunEntity = sun

        // Ambient fill from spherical harmonics. One band is the average radiance of the room
        // and needs exactly three floats; asking for three bands needs twenty-seven, and
        // passing nine throws ArrayIndexOutOfBoundsException at build time.
        //
        // The value is in the same units the intensity scales: 0.30 x 30000 is about 9000 lux,
        // roughly a tenth of the sun above, which is what a cosy room actually looks like.
        val ambient = floatArrayOf(0.30f, 0.28f, 0.25f)
        val indirect = IndirectLight.Builder()
            .irradiance(1, ambient)
            .intensity(30_000f)
            .build(engine)
        scene.indirectLight = indirect
        res.track(indirect)
    }

    private var sunEntity = 0

    // ---------------------------------------------------------------- frame

    /**
     * Tells the drag controller how pixels map to the drum, and remembers the lens for hit
     * testing.
     *
     * Called from the render thread on every surface change, so it must stay trivial.
     */
    override fun onResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        halfFovH = CylinderMath.halfFovH(
            verticalFovDegrees = CylinderMath.FOV_VERTICAL_DEGREES,
            aspect = width.toFloat() / height.toFloat()
        )
        // The gain follows the drum: a bigger drum moves its surface further per radian.
        controller.trackViewport(width, halfFovH, CylinderMath.trackingGain(radius, axis))
    }

    override fun onFrame(frameTimeNanos: Long): Boolean {
        if (frameNanos == 0L) {
            frameNanos = frameTimeNanos
            return true
        }
        val dt = ((frameTimeNanos - frameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        frameNanos = frameTimeNanos
        val moving = controller.tick(dt)
        if (moving) applyRotation()
        return moving
    }

    /**
     * Writes every book's transform for the current drum rotation.
     *
     * One 16-float matrix per book per frame, reused rather than allocated: at 60 fps with
     * dozens of books, allocating here is the difference between a smooth sweep and a stutter
     * every few seconds from garbage collection.
     */
    private fun applyRotation() {
        val e = engine ?: return
        val tm = e.transformManager
        val count = bookEntities.size
        if (count == 0) return
        val rotation = controller.rotation
        for (i in 0 until count) {
            val angle = CylinderMath.angleOf(i, count, rotation)
            val x = CylinderMath.positionX(angle, radius)
            val z = CylinderMath.positionZ(angle, radius, axis)
            val degrees = CylinderMath.facingDegrees(angle)
            buildTransform(x, CylinderMath.BOOK_Y, z, degrees)
            val instance = transforms[i]
            if (instance != 0) tm.setTransform(instance, matrix)
        }
        val front = CylinderMath.frontIndex(count, rotation)
        if (front != lastFrontIndex) {
            lastFrontIndex = front
            onFrontBookChanged?.invoke(front)
        }
    }

    /**
     * Translation then yaw then scale, in column-major order (Filament takes the same layout
     * as OpenGL). Written out rather than calling android.opengl.Matrix so nothing allocates.
     */
    private fun buildTransform(x: Float, y: Float, z: Float, yawDegrees: Float) {
        val a = Math.toRadians(yawDegrees.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        val sx = CylinderMath.BOOK_WIDTH
        val sy = CylinderMath.BOOK_HEIGHT
        val sz = CylinderMath.BOOK_DEPTH
        java.util.Arrays.fill(matrix, 0f)
        // R_y(yaw) * S
        matrix[0] = c * sx;  matrix[1] = 0f; matrix[2] = -s * sx;  matrix[3] = 0f
        matrix[4] = 0f;      matrix[5] = sy; matrix[6] = 0f;       matrix[7] = 0f
        matrix[8] = s * sz;  matrix[9] = 0f; matrix[10] = c * sz;  matrix[11] = 0f
        matrix[12] = x;      matrix[13] = y; matrix[14] = z;       matrix[15] = 1f
    }

    // ---------------------------------------------------------------- buffers

    private fun vertexBuffer(engine: Engine, mesh: ShelfGeometry.Mesh): VertexBuffer {
        val vb = VertexBuffer.Builder()
            .vertexCount(mesh.vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, ShelfGeometry.POSITION_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            // No NORMAL attribute exists: the tangent frame carries the normal.
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS, 0,
                VertexBuffer.AttributeType.FLOAT4, ShelfGeometry.TANGENT_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0, 0,
                VertexBuffer.AttributeType.FLOAT2, ShelfGeometry.UV_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .build(engine)
        val data = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(mesh.vertices)
        vb.setBufferAt(engine, 0, data)
        pendingBuffers.add(data)
        return vb
    }

    private fun indexBuffer(engine: Engine, mesh: ShelfGeometry.Mesh): IndexBuffer {
        val ib = IndexBuffer.Builder()
            .indexCount(mesh.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val data = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        data.asShortBuffer().put(mesh.indices)
        ib.setBuffer(engine, data)
        pendingBuffers.add(data)
        return ib
    }

    // ---------------------------------------------------------------- queries

    /** The book nearest the front of the drum, for the caption under the shelf. */
    fun frontBook(): Int = CylinderMath.frontIndex(bookEntities.size, controller.rotation)

    /** Which book a tap at [screenX] hit, or -1. */
    fun bookAtScreenX(screenX: Float, viewWidth: Float): Int {
        val count = bookEntities.size
        if (count == 0) return -1
        val angle = CylinderMath.angleForScreenX(screenX, viewWidth, halfFovH, radius, axis)
            ?: return -1
        val index = CylinderMath.bookNearAngle(count, controller.rotation, angle)
        if (!CylinderMath.isVisible(
                CylinderMath.angleOf(index, count, controller.rotation),
                CylinderMath.visibleArcDegrees(radius, axis)
            )
        ) return -1
        // A tap on the bare shelf between two books opens nothing, rather than opening whichever
        // book happens to be nearest.
        return if (CylinderMath.isTapOnBook(
                angle, index, count, controller.rotation, radius = radius
            )
        ) index else -1
    }

    override fun onDestroy(engine: Engine) {
        if (sunEntity != 0) {
            EntityManager.get().destroy(sunEntity)
            sunEntity = 0
        }
        staticEntities.forEach { EntityManager.get().destroy(it) }
        staticEntities.clear()
        bookEntities.forEach { EntityManager.get().destroy(it) }
        bookEntities.clear()
        bookInstances.clear()
        transforms.clear()
        resources?.release()
        resources = null
        pendingBuffers.clear()
        engine.flushAndWait()
        this.engine = null
    }
}
