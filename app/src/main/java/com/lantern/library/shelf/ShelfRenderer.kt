package com.lantern.library.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.VertexBuffer
import com.google.android.filament.Viewport
import com.lantern.library.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Everything the GPU needs, built off the main thread before the surface appears. */
class ShelfPrepared(
    val materialBytes: ByteArray,
    val atlas: Bitmap,
    val wood: Bitmap?,
    val room: Bitmap?
)

/**
 * Builds the slow parts of the shelf — the compiled material and the painted atlas — away
 * from the main thread, so the screen never stalls opening.
 */
object ShelfPreparer {

    fun prepare(context: Context, books: List<BookArt>): ShelfPrepared {
        val wood = decode(context, R.drawable.wood_shelf)
        val room = decode(context, R.drawable.library_room)
        return ShelfPrepared(
            materialBytes = ShelfMaterials.load(context),
            atlas = ShelfAtlas.paint(books),
            wood = wood,
            room = room
        )
    }

    private fun decode(context: Context, res: Int): Bitmap? =
        runCatching { BitmapFactory.decodeResource(context.resources, res) }.getOrNull()
}

/**
 * The bookcase scene: one engine, one scene, one camera, one draw call per book.
 *
 * Frames are drawn on demand. A frame happens when something is actually moving, so an idle
 * shelf costs nothing — no 60 fps loop quietly burning battery behind a still picture.
 *
 * Everything lives in a single TextureView. There is no view hierarchy and no SurfaceView,
 * which keeps the scene out of the "flashing black rectangle" family of Android 3D problems
 * and lets Compose draw over it.
 */
class ShelfRenderer(
    private val prepared: ShelfPrepared,
    private val slots: List<ShelfLayout.Slot>,
    private val onStats: (fps: Int, p95Ms: Float, drawCalls: Int, moving: Boolean) -> Unit,
    private val onOpen: (slotIndex: Int) -> Unit,
    private val onTap: (slotIndex: Int) -> Unit = {}
) : TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "ShelfRenderer"
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null
    private var surface: Surface? = null

    private var material: Material? = null
    private var artTexture: Texture? = null
    private var sampler: TextureSampler? = null

    private val instances = ArrayList<MaterialInstance>(72)
    private val textures = ArrayList<Texture>(4)
    /** Keeps the CPU-side buffers reachable until Filament is certainly done with them. */
    private val uploads = ArrayList<Any>(16)
    private val transformSlots = ArrayList<Int>(64)
    private val scratch = FloatArray(16)

    private var width = 1
    private var height = 1

    private val lifts = FloatArray(slots.size)
    private val velocities = FloatArray(slots.size)
    private val targets = FloatArray(slots.size)

    private var selected = -1
    private var scheduled = false
    private var released = false

    private var lastFrameNanos = 0L
    private val frameTimes = FloatArray(60)
    private var frameTimeCount = 0
    private var frameTimeCursor = 0
    private var framesSinceStats = 0
    private var lastStatsNanos = 0L

    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (released) return
            val moving = step(frameTimeNanos)
            draw(frameTimeNanos)
            if (moving) requestFrame()
        }
    }

    // ---------------------------------------------------------------- scene setup

    /** Builds the scene once, the first time a surface exists. */
    private fun ensureScene() {
        if (engine != null) return
        val e = Engine.create()
        engine = e
        renderer = e.createRenderer()
        val sc = e.createScene()
        scene = sc
        val v = e.createView()
        v.setScene(sc)
        // No post-processing: nothing tone maps the painted colours, and no extra
        // full-screen passes get paid for on every frame.
        v.setPostProcessingEnabled(false)
        view = v

        cameraEntity = EntityManager.get().create()
        camera = e.createCamera(cameraEntity).also { v.setCamera(it) }

        val bytes = prepared.materialBytes
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        payload.put(bytes)
        payload.flip()
        uploads.add(payload)
        val mat = Material.Builder().payload(payload, payload.remaining()).build(e)
        material = mat

        val art = upload(prepared.atlas)
        artTexture = art
        val ts = TextureSampler().apply {
            setMinFilter(TextureSampler.MinFilter.LINEAR)
            setMagFilter(TextureSampler.MagFilter.LINEAR)
            setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
            setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
        }
        sampler = ts

        // One shared box mesh, one renderable per book.
        val box = ShelfGeometry.box()
        val vb = vertexBuffer(e, box)
        val ib = indexBuffer(e, box)
        for (slot in slots) {
            val instance = mat.createInstance()
            instance.setParameter("uvRect", cellRect(slot.cell))
            instance.setParameter("art", art, ts)
            instances.add(instance)
            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
                .material(0, instance)
                // Culling off: a few dozen boxes cost nothing, and a book can never vanish
                // because of a bounds or winding mistake.
                .culling(false)
                .build(e, entity)
            val ti = e.getTransformManager().getInstance(entity)
            ShelfLayout.bookTransform(slot, 0f, scratch)
            e.getTransformManager().setTransform(ti, scratch)
            transformSlots.add(ti)
            sc.addEntity(entity)
        }

        // The case and the painted room behind it.
        val caseMesh = ShelfGeometry.caseMesh()
        addStatic(e, caseMesh, prepared.wood, art, ts)
        val roomMesh = ShelfGeometry.roomMesh()
        addStatic(e, roomMesh, prepared.room ?: prepared.wood, art, ts)

        updateProjection()
    }

    private fun addStatic(
        e: Engine,
        mesh: ShelfGeometry.Mesh,
        bitmap: Bitmap?,
        fallback: Texture,
        ts: TextureSampler
    ) {
        val texture = if (bitmap != null) upload(bitmap) else fallback
        val vb = vertexBuffer(e, mesh)
        val ib = indexBuffer(e, mesh)
        val instance = material?.createInstance() ?: return
        // The case and room use the whole texture, not a cell of the atlas.
        instance.setParameter("uvRect", 0f, 0f, 1f, 1f)
        instance.setParameter("art", texture, ts)
        instances.add(instance)
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, instance)
            .culling(false)
            .build(e, entity)
        scene?.addEntity(entity)
    }

    private fun cellRect(cell: Int): FloatArray {
        val col = cell % ShelfLayout.ATLAS_COLS
        val row = cell / ShelfLayout.ATLAS_COLS
        return floatArrayOf(
            col.toFloat() / ShelfLayout.ATLAS_COLS,
            row.toFloat() / ShelfLayout.ATLAS_ROWS,
            1f / ShelfLayout.ATLAS_COLS,
            1f / ShelfLayout.ATLAS_ROWS
        )
    }

    private fun vertexBuffer(e: Engine, mesh: ShelfGeometry.Mesh): VertexBuffer {
        val vb = VertexBuffer.Builder()
            .vertexCount(mesh.vertexCount)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3,
                ShelfGeometry.POSITION_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0, 0,
                VertexBuffer.AttributeType.FLOAT2,
                ShelfGeometry.UV_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .attribute(
                VertexBuffer.VertexAttribute.COLOR, 0,
                VertexBuffer.AttributeType.FLOAT4,
                ShelfGeometry.COLOR_OFFSET, ShelfGeometry.STRIDE_BYTES
            )
            .build(e)
        val data = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().put(mesh.vertices)
        vb.setBufferAt(e, 0, data)
        uploads.add(data)
        return vb
    }

    private fun indexBuffer(e: Engine, mesh: ShelfGeometry.Mesh): IndexBuffer {
        val ib = IndexBuffer.Builder()
            .indexCount(mesh.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(e)
        val data = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        data.asShortBuffer().put(mesh.indices)
        ib.setBuffer(e, data)
        uploads.add(data)
        return ib
    }

    /** Uploads a bitmap as an RGBA8 texture. */
    private fun upload(bitmap: Bitmap): Texture {
        val e = engine ?: error("engine is not ready")
        val tex = Texture.Builder()
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .width(bitmap.width)
            .height(bitmap.height)
            .levels(0)
            .build(e)
        val data = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(data)
        data.flip()
        tex.setImage(e, 0, Texture.PixelBufferDescriptor(data, Texture.Format.RGBA, Texture.Type.UBYTE))
        tex.generateMipmaps(e)
        uploads.add(data)
        textures.add(tex)
        return tex
    }

    // ---------------------------------------------------------------- surface

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
        if (released) return
        try {
            ensureScene()
            val s = Surface(surfaceTexture)
            surface = s
            swapChain = engine?.createSwapChain(s)
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            updateProjection()
            requestFrame()
        } catch (t: Throwable) {
            Log.e(TAG, "the shelf could not start", t)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        updateProjection()
        requestFrame()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        swapChain?.let { engine?.destroySwapChain(it) }
        swapChain = null
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun updateProjection() {
        val cam = camera ?: return
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        val p = ShelfLayout.projection(aspect, 0.05f)
        cam.setProjection(
            Camera.Projection.PERSPECTIVE,
            p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
            p[4].toDouble(), p[5].toDouble()
        )
        // Eye at the origin looking down -z: the identity model matrix.
        cam.setModelMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            )
        )
        view?.setViewport(Viewport(0, 0, width, height))
    }

    // ---------------------------------------------------------------- frame loop

    /** Advances the springs; returns true while anything is still moving. */
    private fun step(frameTimeNanos: Long): Boolean {
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
        var dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        if (dt <= 0f) dt = 1f / 60f
        dt = dt.coerceAtMost(1f / 20f)

        val tcm = engine?.getTransformManager() ?: return false
        var moving = false
        for (i in slots.indices) {
            val settled = lifts[i] == targets[i] && velocities[i] == 0f
            if (settled) continue
            val next = ShelfLayout.springStep(lifts[i], velocities[i], targets[i], dt)
            lifts[i] = next.first
            velocities[i] = next.second
            if (lifts[i] != targets[i]) moving = true
            ShelfLayout.bookTransform(slots[i], lifts[i], scratch)
            val ti = transformSlots.getOrNull(i) ?: -1
            if (ti >= 0) tcm.setTransform(ti, scratch)
        }
        return moving
    }

    private fun draw(frameTimeNanos: Long) {
        val r = renderer ?: return
        val sc = swapChain ?: return
        val v = view ?: return
        val started = System.nanoTime()
        try {
            if (r.beginFrame(sc, frameTimeNanos)) {
                r.render(v)
                r.endFrame()
                recordFrameTime((System.nanoTime() - started) / 1_000_000f)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
        }
    }

    private fun recordFrameTime(ms: Float) {
        frameTimes[frameTimeCursor] = ms
        frameTimeCursor = (frameTimeCursor + 1) % frameTimes.size
        if (frameTimeCount < frameTimes.size) frameTimeCount++
        framesSinceStats++
        val now = System.nanoTime()
        if (lastStatsNanos == 0L) lastStatsNanos = now
        if (now - lastStatsNanos < 500_000_000L) return
        val elapsed = (now - lastStatsNanos) / 1_000_000_000f
        lastStatsNanos = now
        val fps = (framesSinceStats / elapsed).toInt()
        framesSinceStats = 0
        val sorted = frameTimes.copyOf(frameTimeCount).also { it.sort() }
        val p95 = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.95f).toInt()]
        onStats(fps, p95, slots.size + 2, isMoving())
    }

    private fun isMoving(): Boolean {
        for (i in lifts.indices) if (lifts[i] != targets[i]) return true
        return false
    }

    private fun requestFrame() {
        if (scheduled || released) return
        scheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- input

    /** A tap lifts a book, opens the one already lifted, or puts everything back. */
    fun tap(x: Float, y: Float) {
        val pick = ShelfLayout.pickAngle(x, y, width.toFloat(), height.toFloat())
            ?: return clearSelection()
        val slot = ShelfLayout.slotAt(slots, pick.first, pick.second)
            ?: return clearSelection()
        if (selected == slot.index) {
            onOpen(slot.index)
        } else {
            if (selected in targets.indices) targets[selected] = 0f
            selected = slot.index
            targets[slot.index] = 1f
        }
        onTap(selected)
        requestFrame()
    }

    private fun clearSelection() {
        if (selected < 0) return
        if (selected in targets.indices) targets[selected] = 0f
        selected = -1
        onTap(-1)
        requestFrame()
    }

    /** Index of the lifted book, or -1. */
    fun liftedSlot(): Int = selected

    fun release() {
        released = true
        choreographer.removeFrameCallback(frameCallback)
        scheduled = false
        val e = engine ?: return
        swapChain?.let { e.destroySwapChain(it) }
        swapChain = null
        surface?.release()
        surface = null
        textures.forEach { e.destroyTexture(it) }
        textures.clear()
        instances.forEach { runCatching { e.destroyMaterialInstance(it) } }
        instances.clear()
        uploads.clear()
        transformSlots.clear()
        material?.let { e.destroyMaterial(it) }
        material = null
        scene?.let { e.destroyScene(it) }
        scene = null
        view?.let { e.destroyView(it) }
        view = null
        renderer?.let { e.destroyRenderer(it) }
        renderer = null
        if (cameraEntity != 0) {
            e.destroyCameraComponent(cameraEntity)
            EntityManager.get().destroy(cameraEntity)
            cameraEntity = 0
        }
        camera = null
        engine = null
        e.destroy()
    }
}
