package com.lantern.library.shelf3d

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.VertexBuffer
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper

/**
 * Owns one Filament engine and everything created from it, bound to a SurfaceView's lifecycle.
 *
 * The container never touches the Android UI beyond the surface it is given, and it is created
 * with the application context, so it cannot outlive or leak an Activity.
 *
 * The frame loop is demand-driven: a frame is drawn when the scene says something changed, or
 * when a surface arrives. A still shelf therefore draws once and then stops — sitting at a
 * steady 60 fps to show an unchanging picture is the single easiest way to drain a battery.
 */
class FilamentShelfEngine(
    context: Context,
    private val surfaceView: SurfaceView,
    private val lifecycleOwner: LifecycleOwner,
    private val content: ShelfContent
) : DefaultLifecycleObserver {

    private companion object {
        const val TAG = "FilamentShelfEngine"
    }

    /** Where the frame content hooks in. Implemented by the scene. */
    interface ShelfContent {
        /** Called once, after the engine exists and before the first frame. */
        fun onCreate(engine: Engine, scene: Scene)

        /** Called when the viewport changes shape. */
        fun onResize(width: Int, height: Int)

        /**
         * How far in front of the camera the shelf's front rank of books stands, in metres.
         *
         * The camera is tilted by a fixed angle, so it needs to know how far away the thing it is
         * looking at is: a fixed look-at point would put the tilt in a different place for every
         * library size.
         */
        fun cameraDistance(): Float = 1f

        /**
         * Called before each frame. Returns true if the scene is still animating, which keeps
         * the loop alive; false lets the loop stop until something asks for a frame again.
         */
        fun onFrame(frameTimeNanos: Long): Boolean

        /** Called before the engine is destroyed. Release native resources here. */
        fun onDestroy(engine: Engine)
    }

    // The application context, so nothing here can hold a screen that has gone home.
    private val appContext: Context = context.applicationContext

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var swapChain: SwapChain? = null

    private var uiHelper: UiHelper? = null
    private var displayHelper: DisplayHelper? = null

    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private var frameCallback: Choreographer.FrameCallback? = null

    /** True between surface creation and destruction. Nothing renders outside this window. */
    private var surfaceReady = false
    private var destroyed = false
    private var created = false
    private var width = 1
    private var height = 1

    /** Reports why the scene could not start, rather than leaving a black rectangle. */
    var onError: ((String) -> Unit)? = null

    init {
        lifecycleOwner.lifecycle.addObserver(this)
        // The view can be constructed while the host is already resumed, so create now if the
        // lifecycle says we are visible. Without this the first frame never happens because
        // onResume() fired before the observer was attached.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            start()
        }
    }

    /** Creates the engine. Safe to call twice; the second call does nothing. */
    fun start() {
        if (destroyed || engine != null) return
        try {
            // Filament's Java classes are thin JNI wrappers and deliberately load nothing
            // themselves: touching this class is what runs System.loadLibrary("filament-jni").
            // Without it the first native call dies with "No implementation found for
            // nCreateBuilder". This one line is the difference between a working engine and a
            // JNI UnsatisfiedLinkError.
            Filament.init()

            val e = Engine.create()
            engine = e
            val r = e.createRenderer()
            renderer = r
            val s = e.createScene()
            scene = s
            val v = e.createView()
            view = v
            v.scene = s
            // Post-processing stays on, and only for tone mapping. The sun in this scene is at
            // daylight intensity and the fill lights are thousands of lux; without the tone
            // mapping pass those values clip, and every lit board turns into a flat white
            // shape with its edges burned away. Bloom and anti-aliasing are left off below.
            v.isPostProcessingEnabled = true
            r.clearOptions = Renderer.ClearOptions().apply {
                // A warm near-black, not pure black: if the case ever leaves a gap, the frame
                // reads as an unlit room rather than as a hole in the phone.
                clearColor = doubleArrayOf(0.02, 0.014, 0.010, 1.0)
                clear = true
            }

            cameraEntity = EntityManager.get().create()
            camera = e.createCamera(cameraEntity).also { v.camera = it }

            content.onCreate(e, s)
            created = true

            displayHelper = DisplayHelper(appContext)
            attachSurfaceView(e, v)
            frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frameScheduled = false
                    renderFrame(frameTimeNanos)
                }
            }
            invalidate()
        } catch (t: Throwable) {
            Log.e(TAG, "could not start the shelf engine", t)
            reportFailure(t)
        }
    }

    /**
     * Hooks Filament to the SurfaceView.
     *
     * UiHelper does the surface bookkeeping: it gives back a native window when one exists,
     * tells us when it goes away, and exposes isReadyToRender so no frame is ever drawn against
     * a missing surface. Creating a swap chain without that check is the usual way a 3D screen
     * crashes on rotation or on returning from the background.
     */
    private fun attachSurfaceView(e: Engine, v: View) {
        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        helper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                if (destroyed) return
                swapChain?.let { e.destroySwapChain(it) }
                swapChain = e.createSwapChain(surface)
                renderer?.let { displayHelper?.attach(it, surfaceView.display) }
                surfaceReady = true
                invalidate()
            }

            override fun onDetachedFromSurface() {
                surfaceReady = false
                swapChain?.let { chain ->
                    e.destroySwapChain(chain)
                    // Make sure the GPU has finished with the buffers before they go away.
                    e.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = w.coerceAtLeast(1)
                height = h.coerceAtLeast(1)
                updateProjection(v)
                content.onResize(width, height)
                invalidate()
            }
        }
        uiHelper = helper
        // Attaching last: UiHelper calls back immediately if the surface already exists.
        helper.attachTo(surfaceView)
    }

    private fun updateProjection(v: View) {
        val aspect = width.toDouble() / height.toDouble().coerceAtLeast(1.0)
        // The scene is framed from these numbers as much as from the camera's: shelf3d sizes a
        // book to fill a good part of the screen at CylinderMath.FOV_VERTICAL_DEGREES, and the
        // drag gain and every tap depend on it. Changing one without the other breaks both.
        camera?.setProjection(
            CylinderMath.FOV_VERTICAL_DEGREES.toDouble(),
            aspect,
            0.02,
            30.0,
            Camera.Fov.VERTICAL
        )
        // Look very slightly down and across at the case.
        //
        // Dead-on and centred, a shelf of boxes has no depth at all: every book shows the same
        // face, all the boards are edge-on, and the picture is indistinguishable from a flat
        // drawing. A few degrees of pitch puts the tops of the boards and the tops of the books
        // in view, which is the cue that says "this is a solid object". A degree or two of yaw
        // does the same for the sides. Both are proportional to the distance, so the angle is
        // the same for a small library on a small drum and a large one on a wide drum.
        val distance = content.cameraDistance().coerceAtLeast(0.2f)
        // About ten degrees of downward pitch, which is what puts the tops of the boards and the
        // tops of the books into the frame. Without it a shelf of boxes shows only its front faces
        // and reads as a flat picture however good the geometry is.
        camera?.lookAt(
            (distance * 0.03).toDouble(), (distance * 0.18).toDouble(), 0.0,
            0.0, -(distance * 0.02).toDouble(), -distance.toDouble(),
            0.0, 1.0, 0.0
        )
        v.viewport = Viewport(0, 0, width, height)
    }

    /** Asks for one frame. Cheap and idempotent: the loop stops itself when idle. */
    fun invalidate() {
        if (destroyed || frameScheduled) return
        val callback = frameCallback ?: return
        frameScheduled = true
        choreographer.postFrameCallback(callback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        if (destroyed) return
        val r = renderer ?: return
        val v = view ?: return
        val chain = swapChain ?: return
        // Two guards, both necessary: the surface must exist, and UiHelper must agree it is
        // ready. Rendering between surface loss and recreation produces crashes that only
        // show up on real devices.
        if (!surfaceReady || uiHelper?.isReadyToRender != true) return
        try {
            if (r.beginFrame(chain, frameTimeNanos)) {
                val animating = content.onFrame(frameTimeNanos)
                r.render(v)
                r.endFrame()
                // Keep drawing only while something is actually moving.
                if (animating) invalidate()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            reportFailure(t)
        }
    }

    private fun reportFailure(t: Throwable) {
        val message = when {
            t is UnsatisfiedLinkError ->
                "The renderer's native library is missing on this phone."
            t.message?.isNotBlank() == true -> "The shelf could not be drawn: ${t.message}"
            else -> "The shelf could not be drawn: ${t.javaClass.simpleName}"
        }
        onError?.invoke(message)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume(owner: LifecycleOwner) {
        start()
        invalidate()
    }

    override fun onPause(owner: LifecycleOwner) {
        // Stop the loop but keep the engine: rebuilding it on every pause would be slow and
        // would throw away textures that are still valid.
        choreographer.removeFrameCallback(frameCallback ?: return)
        frameScheduled = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
        lifecycleOwner.lifecycle.removeObserver(this)
    }

    /**
     * Tears everything down in the reverse of the order it was created.
     *
     * Getting this wrong is what leaks native memory across rotations: an engine destroyed
     * before its swap chain or its content's resources leaves the GPU holding buffers with no
     * owner. Every resource created here is released here, exactly once.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        surfaceReady = false
        frameCallback?.let { choreographer.removeFrameCallback(it) }
        frameCallback = null
        frameScheduled = false

        val e = engine ?: return
        try {
            uiHelper?.detach()
            uiHelper = null
            displayHelper?.detach()
            displayHelper = null

            if (created) {
                runCatching { content.onDestroy(e) }
                created = false
            }

            swapChain?.let {
                e.destroySwapChain(it)
                e.flushAndWait()
            }
            swapChain = null

            view?.let { e.destroyView(it) }
            view = null
            scene?.let { e.destroyScene(it) }
            scene = null
            renderer?.let { e.destroyRenderer(it) }
            renderer = null
            if (cameraEntity != 0) {
                // Filament has destroyCameraComponent(), not destroyCamera(). The entity that
                // carried the component has to be released as well.
                e.destroyCameraComponent(cameraEntity)
                EntityManager.get().destroy(cameraEntity)
                cameraEntity = 0
            }
            camera = null
            engine = null
            e.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "teardown failed", t)
        }
    }
}

/**
 * Small holder for the native objects a scene owns, so a scene cannot forget to free one.
 * Filament objects are not garbage collected: each has to be destroyed explicitly.
 */
class ShelfResources(private val engine: Engine) {
    private val vertices = ArrayList<VertexBuffer>()
    private val indices = ArrayList<IndexBuffer>()
    private val materials = ArrayList<Material>()
    private val instances = ArrayList<MaterialInstance>()
    private val textures = ArrayList<Texture>()
    private var light: IndirectLight? = null

    fun track(buffer: VertexBuffer): VertexBuffer = buffer.also { vertices.add(it) }
    fun track(buffer: IndexBuffer): IndexBuffer = buffer.also { indices.add(it) }
    fun track(material: Material): Material = material.also { materials.add(it) }
    fun track(instance: MaterialInstance): MaterialInstance = instance.also { instances.add(it) }
    fun track(texture: Texture): Texture = texture.also { textures.add(it) }
    fun track(indirectLight: IndirectLight): IndirectLight =
        indirectLight.also { light = it }

    fun release() {
        instances.forEach { runCatching { engine.destroyMaterialInstance(it) } }
        instances.clear()
        textures.forEach { runCatching { engine.destroyTexture(it) } }
        textures.clear()
        vertices.forEach { runCatching { engine.destroyVertexBuffer(it) } }
        vertices.clear()
        indices.forEach { runCatching { engine.destroyIndexBuffer(it) } }
        indices.clear()
        materials.forEach { runCatching { engine.destroyMaterial(it) } }
        materials.clear()
        light?.let { runCatching { engine.destroyIndirectLight(it) } }
        light = null
    }
}
