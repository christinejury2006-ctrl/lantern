package com.lantern.library.shelf3d

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.lifecycle.LifecycleOwner
import kotlin.math.hypot

/**
 * The SurfaceView that shows the drum, and the only place touch input is handled.
 *
 * Drags feed [CylinderRotationController]; a drag that ends in a flick keeps the drum coasting
 * and then settles it onto the nearest book. Every accepted gesture asks the engine for frames
 * until the drum stops, and the engine stops on its own when it does.
 *
 * SurfaceView (rather than TextureView) because this is a full-bleed 3D scene: the surface is
 * its own hardware layer, so the GPU composites it directly instead of copying a texture
 * through the view hierarchy every frame. The Compose HUD still draws on top, because the
 * surface is placed behind the window by default.
 */
@SuppressLint("ViewConstructor")
class CylinderShelfSurfaceView(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val controller: CylinderRotationController,
    private val materialBytes: Map<String, ByteArray>,
    private val bookCount: Int,
    bookColors: List<Int>,
    private val spineAtlas: SpineAtlas?,
    onError: (String) -> Unit,
    private val onFrontBook: (Int) -> Unit,
    private val onTapBook: (Int) -> Unit,
    private val onLongPressBook: (Int) -> Unit = {}
) : SurfaceView(context) {

    private val scene = CylinderShelfScene(
        bookCount = { bookCount },
        controller = controller,
        materialBytes = materialBytes,
        bookColors = bookColors,
        spineAtlas = spineAtlas
    )

    private val engine: FilamentShelfEngine

    /** Pointer id of the finger currently dragging, or -1. */
    private var activePointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val velocityTracker = android.view.VelocityTracker.obtain()

    init {
        // Default z-order: the surface sits behind the window, so Compose draws over it.
        // setZOrderOnTop(true) would invert that and hide the HUD.
        setZOrderOnTop(false)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)

        engine = FilamentShelfEngine(context, this, lifecycleOwner, scene)
        engine.onError = onError
        scene.onSceneError = onError
        scene.onFrontBookChanged = onFrontBook
        onFrontBook(CylinderMath.frontIndex(bookCount, controller.rotation))
        isFocusable = true
    }

    /** Starts the engine if the lifecycle said we are already visible. */
    fun start() {
        engine.start()
    }

    fun release() {
        velocityTracker.recycle()
        engine.destroy()
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                // Catch the drum where it is: a touch during a coast should stop it dead, the
                // way a hand on a spinning rack does.
                controller.reset()
                engine.invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointer < 0) return false
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return false
                val x = event.getX(index)
                val dx = x - lastX
                lastX = x
                lastY = event.getY(index)
                velocityTracker.addMovement(event)
                if (controller.onDrag(dx)) {
                    engine.invalidate()
                    onFrontBook(CylinderMath.frontIndex(bookCount, controller.rotation))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointer < 0) return false
                velocityTracker.addMovement(event)
                velocityTracker.computeCurrentVelocity(1000)
                val vx = velocityTracker.getXVelocity(activePointer)
                val moved = hypot(event.x - downX, event.y - downY)
                val quick = event.eventTime - downTime < 260
                activePointer = -1

                if (event.actionMasked == MotionEvent.ACTION_UP && moved < 24f && quick) {
                    // A tap, not a drag: select the book under the finger.
                    val hit = scene.bookAtScreenX(event.x, viewWidth)
                    if (hit >= 0) onTapBook(hit)
                    engine.invalidate()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && moved < 24f) {
                    // Held still for longer than a tap: the same spine, asked a different
                    // question. Time is measured on the event, so no timer can outlive the touch.
                    val hit = scene.bookAtScreenX(event.x, viewWidth)
                    if (hit >= 0) onLongPressBook(hit)
                    engine.invalidate()
                } else {
                    controller.onRelease(vx)
                    engine.invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == activePointer) {
                    // the dragging finger left; hand over to another if there is one
                    val next = if (index == 0) 1 else 0
                    activePointer = if (next < event.pointerCount) event.getPointerId(next) else -1
                    lastX = if (next < event.pointerCount) event.getX(next) else lastX
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        engine.destroy()
    }
}
