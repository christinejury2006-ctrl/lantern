package com.lantern.library.shelf

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/**
 * A minimal EGL context bound to a [SurfaceTexture].
 *
 * This is the whole of the "engine" the shelf needs: about a hundred lines of standard EGL
 * setup. Everything else — geometry, shading, motion — is code in this package, which is the
 * point: there is no third-party renderer to satisfy, no material compiler, and no
 * preconditions between here and a drawn frame.
 *
 * Using a SurfaceTexture (rather than a SurfaceView's own surface) means the scene composites
 * inside the normal view hierarchy, so Compose can draw over it and the room can be blurred
 * behind an open book.
 */
class ShelfEgl {

    private companion object {
        const val TAG = "ShelfEgl"
    }

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var width = 0
    private var height = 0
    private var valid = false

    /** Creates the context and a window surface over [texture]. Call on the render thread. */
    fun start(texture: SurfaceTexture, w: Int, h: Int): Boolean {
        release()
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return fail("no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return fail("eglInitialize failed (0x${Integer.toHexString(EGL14.eglGetError())})")
            }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(
                    display, configAttribs, 0, configs, 0, 1, count, 0
                ) || count[0] == 0
            ) {
                return fail("no EGL config with an 8-bit colour and 16-bit depth buffer")
            }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext failed")

            // A SurfaceTexture is accepted directly as a native window.
            surface = EGL14.eglCreateWindowSurface(
                display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface failed")
            if (!makeCurrent()) return fail("eglMakeCurrent failed")
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            valid = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start EGL", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    fun makeCurrent(): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    fun resize(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
    }

    /** Presents the frame. Returns false if the surface has gone away. */
    fun swap(): Boolean = try {
        EGL14.eglSwapBuffers(display, surface)
    } catch (t: Throwable) {
        Log.w(TAG, "swap failed", t)
        false
    }

    val surfaceWidth: Int get() = width
    val surfaceHeight: Int get() = height

    fun release() {
        valid = false
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun fail(message: String): Boolean {
        Log.e(TAG, message)
        release()
        return false
    }
}
