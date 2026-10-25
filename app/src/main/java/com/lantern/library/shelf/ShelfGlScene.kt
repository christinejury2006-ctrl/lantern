package com.lantern.library.shelf

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The bookcase, drawn directly with OpenGL ES 2.0.
 *
 * The scene is a few dozen boxes plus two swept surfaces, so an engine would only add layers
 * between the maths and the pixels. Here the whole pipeline is visible: one shader, three
 * textures, one draw call per book.
 *
 * Frames are drawn on demand. The render thread sleeps until something moves; while a book is
 * lifting it draws every frame, and once everything settles it stops dead. An idle shelf
 * therefore costs no work at all.
 */
class ShelfGlScene(
    private val prepared: ShelfPrepared,
    private val slots: List<ShelfLayout.Slot>,
    private val onFirstFrame: () -> Unit = {},
    private val onStats: (fps: Int, p95Ms: Float, drawCalls: Int, moving: Boolean) -> Unit = { _, _, _, _ -> },
    private val onError: (String) -> Unit = {}
) : TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "ShelfGlScene"
    }

    // ---- shader: unlit, art sampled through a per-book window, shade from the vertex colour.
    // No lighting maths, no normals, no shadow maps: the room is painted and each face carries
    // the light it catches, so there is nothing here that can come out black.
    private val vertexShader = """
        uniform mat4 uViewProj;
        uniform mat4 uModel;
        uniform vec4 uUvRect;
        attribute vec3 aPos;
        attribute vec2 aUv;
        attribute vec4 aShade;
        varying vec2 vUv;
        varying vec3 vShade;
        void main() {
            vUv = aUv * uUvRect.zw + uUvRect.xy;
            vShade = aShade.rgb;
            gl_Position = uViewProj * (uModel * vec4(aPos, 1.0));
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform sampler2D uArt;
        varying vec2 vUv;
        varying vec3 vShade;
        void main() {
            vec4 t = texture2D(uArt, vUv);
            gl_FragColor = vec4(t.rgb * vShade, 1.0);
        }
    """.trimIndent()

    private val egl = ShelfEgl()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var renderThread: Thread? = null
    private val lock = Object()
    private var dirty = false
    private var quit = false

    private var program = 0
    private var aPos = -1
    private var aUv = -1
    private var aShade = -1
    private var uViewProj = -1
    private var uModel = -1
    private var uUvRect = -1
    private var uArt = -1

    private var atlasTex = 0
    private var woodTex = 0
    private var roomTex = 0

    private var boxVertices = 0
    private var boxIndices = 0
    private var boxIndexCount = 0
    private var caseVertices = 0
    private var caseIndices = 0
    private var caseIndexCount = 0
    private var roomVertices = 0
    private var roomIndices = 0
    private var roomIndexCount = 0

    private var viewWidth = 1
    private var viewHeight = 1

    private val rows = ShelfLayout.rowsUsed(slots)

    private val lifts = FloatArray(slots.size)
    private val velocities = FloatArray(slots.size)
    private val targets = FloatArray(slots.size)
    private val model = FloatArray(16)
    private val viewProj = FloatArray(16)

    @Volatile private var selected = -1
    private var firstFrameReported = false
    private var failureReported = false
    private var lastFrameNanos = 0L

    private val frameTimes = FloatArray(60)
    private var frameTimeCount = 0
    private var frameTimeCursor = 0
    private var framesSinceStats = 0
    private var lastStatsNanos = 0L

    // ---------------------------------------------------------------- surface lifecycle

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
        viewWidth = w.coerceAtLeast(1)
        viewHeight = h.coerceAtLeast(1)
        startThread(texture, w, h)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
        viewWidth = w.coerceAtLeast(1)
        viewHeight = h.coerceAtLeast(1)
        // The surface is not recreated on resize. The render thread notices the new size on
        // the next frame and fixes the projection there, because EGL belongs to that thread.
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopThread()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun startThread(texture: SurfaceTexture, w: Int, h: Int) {
        stopThread()
        synchronized(lock) {
            quit = false
            dirty = true
        }
        val thread = Thread {
            if (!egl.start(texture, w, h)) {
                reportFailure("This phone could not create a graphics context for the shelf.")
                return@Thread
            }
            try {
                setup()
                loop()
            } catch (t: Throwable) {
                Log.e(TAG, "the shelf stopped", t)
                reportFailure(describe(t))
            } finally {
                teardown()
                egl.release()
            }
        }
        thread.name = "shelf-render"
        renderThread = thread
        thread.start()
    }

    private fun stopThread() {
        val thread = renderThread ?: return
        synchronized(lock) {
            quit = true
            lock.notifyAll()
        }
        runCatching { thread.join(1200) }
        renderThread = null
    }

    /** Turns a graphics exception into a sentence that says what happened. */
    private fun describe(t: Throwable): String {
        val detail = t.message?.takeIf { it.isNotBlank() }
        return when {
            t is UnsatisfiedLinkError -> "The graphics library is not available on this phone."
            detail != null -> "Drawing the room failed: $detail"
            else -> "Drawing the room failed: ${t.javaClass.simpleName}"
        }
    }

    private fun reportFailure(message: String) {
        if (failureReported) return
        failureReported = true
        mainHandler.post { runCatching { onError(message) } }
    }

    // ---------------------------------------------------------------- render loop

    private fun loop() {
        while (true) {
            synchronized(lock) {
                while (!dirty && !quit) lock.wait()
                if (quit) return
                dirty = false
            }
            val now = System.nanoTime()
            val moving = step(now)
            val started = System.nanoTime()
            draw()
            recordFrame((System.nanoTime() - started) / 1_000_000f)
            if (moving) requestRender()
        }
    }

    private fun requestRender() {
        synchronized(lock) {
            dirty = true
            lock.notifyAll()
        }
    }

    /** Advances every spring. Returns true while something is still moving. */
    private fun step(frameNanos: Long): Boolean {
        if (lastFrameNanos == 0L) lastFrameNanos = frameNanos
        var dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameNanos
        if (dt <= 0f) dt = 1f / 60f
        dt = dt.coerceAtMost(1f / 20f)
        var moving = false
        for (i in slots.indices) {
            if (lifts[i] == targets[i] && velocities[i] == 0f) continue
            val next = ShelfLayout.springStep(lifts[i], velocities[i], targets[i], dt)
            lifts[i] = next.first
            velocities[i] = next.second
            if (lifts[i] != targets[i]) moving = true
        }
        return moving
    }

    // ---------------------------------------------------------------- setup

    private fun setup() {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        aShade = GLES20.glGetAttribLocation(program, "aShade")
        uViewProj = GLES20.glGetUniformLocation(program, "uViewProj")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uUvRect = GLES20.glGetUniformLocation(program, "uUvRect")
        uArt = GLES20.glGetUniformLocation(program, "uArt")

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClearColor(0.09f, 0.06f, 0.05f, 1f)

        atlasTex = upload(prepared.atlas, mipmaps = true)
        woodTex = upload(prepared.wood, mipmaps = true)
        roomTex = upload(prepared.room, mipmaps = true)

        val box = ShelfGeometry.box()
        boxVertices = uploadVertices(box)
        boxIndices = uploadIndices(box)
        boxIndexCount = box.indexCount

        val case = ShelfGeometry.caseMesh(rows)
        caseVertices = uploadVertices(case)
        caseIndices = uploadIndices(case)
        caseIndexCount = case.indexCount

        val room = ShelfGeometry.roomMesh(rows)
        roomVertices = uploadVertices(room)
        roomIndices = uploadIndices(room)
        roomIndexCount = room.indexCount

        updateProjection()
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("shader link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun uploadVertices(mesh: ShelfGeometry.Mesh): Int {
        val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(mesh.vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(mesh.vertices); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            mesh.vertices.size * 4,
            buffer,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun uploadIndices(mesh: ShelfGeometry.Mesh): Int {
        val buffer: ShortBuffer = ByteBuffer
            .allocateDirect(mesh.indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(mesh.indices); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indices.size * 2,
            buffer,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /**
     * Uploads a bitmap. The atlas is a power of two so it gets a mip chain; the two room
     * photographs are not, and OpenGL ES 2.0 only allows mipmapping on power-of-two textures,
     * so those sample with a plain linear filter and clamped wrapping.
     */
    private fun upload(bitmap: Bitmap?, mipmaps: Boolean): Int {
        if (bitmap == null) return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val powerOfTwo = Integer.bitCount(bitmap.width) == 1 && Integer.bitCount(bitmap.height) == 1
        if (mipmaps && powerOfTwo) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR
            )
        } else {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun updateProjection() {
        val aspect = egl.surfaceWidth.toFloat() / egl.surfaceHeight.toFloat().coerceAtLeast(1f)
        val near = 0.05f
        val far = 8f
        val tanH = ShelfLayout.tanHFov(aspect, rows)
        val tanV = ShelfLayout.tanVFov(rows)
        // Column-major perspective with the eye at the origin looking down -z.
        java.util.Arrays.fill(viewProj, 0f)
        viewProj[0] = 1f / tanH
        viewProj[5] = 1f / tanV
        viewProj[10] = -(far + near) / (far - near)
        viewProj[11] = -1f
        viewProj[14] = -2f * far * near / (far - near)
        viewProj[15] = 0f
        // Aim the camera at the middle of the case. Without this the frustum is centred on
        // y = 0 while the case sits low, and the bottom board falls off the screen.
        val centre = ShelfLayout.caseCentreY(rows)
        viewProj[13] = -centre * (1f / tanV)
        viewProj[15] = 0f
    }

    // ---------------------------------------------------------------- drawing

    private fun draw() {
        if (egl.surfaceWidth != viewWidth || egl.surfaceHeight != viewHeight) {
            egl.resize(viewWidth, viewHeight)
            updateProjection()
        }
        GLES20.glViewport(0, 0, egl.surfaceWidth, egl.surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform1i(uArt, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        var draws = 0

        // the room and the case are static: identity model, whole texture
        identity(model)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        if (roomIndexCount > 0 && roomTex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, roomTex)
            GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
            bindAndDraw(roomVertices, roomIndices, roomIndexCount)
            draws++
        }
        if (caseIndexCount > 0 && woodTex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, woodTex)
            GLES20.glUniform4f(uUvRect, 0f, 0f, 1f, 1f)
            bindAndDraw(caseVertices, caseIndices, caseIndexCount)
            draws++
        }

        // the books
        if (atlasTex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex)
            for (slot in slots) {
                ShelfLayout.bookTransform(slot, lifts[slot.index], model)
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
                val rect = cellRect(slot.cell)
                GLES20.glUniform4f(uUvRect, rect[0], rect[1], rect[2], rect[3])
                bindAndDraw(boxVertices, boxIndices, boxIndexCount)
                draws++
            }
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        if (!egl.swap()) {
            reportFailure("The graphics surface went away while drawing.")
            return
        }
        if (!firstFrameReported) {
            firstFrameReported = true
            // The frame is presented. Nothing else needs to happen for it to be visible, so
            // say so now: an idle shelf draws once and then stops.
            mainHandler.post { runCatching { onFirstFrame() } }
        }
        lastDrawCalls = draws
    }

    private var lastDrawCalls = 0

    private fun bindAndDraw(vertices: Int, indices: Int, count: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertices)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices)
        val stride = ShelfGeometry.STRIDE_BYTES
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(
            aPos, 3, GLES20.GL_FLOAT, false, stride, ShelfGeometry.POSITION_OFFSET
        )
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(
            aUv, 2, GLES20.GL_FLOAT, false, stride, ShelfGeometry.UV_OFFSET
        )
        GLES20.glEnableVertexAttribArray(aShade)
        GLES20.glVertexAttribPointer(
            aShade, 4, GLES20.GL_FLOAT, false, stride, ShelfGeometry.COLOR_OFFSET
        )
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0)
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

    private fun identity(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
    }

    private fun recordFrame(ms: Float) {
        frameTimes[frameTimeCursor] = ms
        frameTimeCursor = (frameTimeCursor + 1) % frameTimes.size
        if (frameTimeCount < frameTimes.size) frameTimeCount++
        framesSinceStats++
        val now = System.nanoTime()
        if (lastStatsNanos == 0L) lastStatsNanos = now - 500_000_000L
        if (now - lastStatsNanos < 500_000_000L) return
        val elapsed = (now - lastStatsNanos) / 1_000_000_000f
        lastStatsNanos = now
        val fps = (framesSinceStats / elapsed).toInt()
        framesSinceStats = 0
        val sorted = frameTimes.copyOf(frameTimeCount).also { it.sort() }
        val p95 = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.95f).toInt()]
        val calls = lastDrawCalls
        val moving = isMoving()
        mainHandler.post { runCatching { onStats(fps, p95, calls, moving) } }
    }

    private fun isMoving(): Boolean {
        for (i in lifts.indices) if (lifts[i] != targets[i]) return true
        return false
    }

    private fun teardown() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        val textures = intArrayOf(atlasTex, woodTex, roomTex).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        atlasTex = 0
        woodTex = 0
        roomTex = 0
        val buffers = intArrayOf(
            boxVertices, boxIndices, caseVertices, caseIndices, roomVertices, roomIndices
        ).filter { it != 0 }.toIntArray()
        if (buffers.isNotEmpty()) GLES20.glDeleteBuffers(buffers.size, buffers, 0)
        boxVertices = 0
        boxIndices = 0
        caseVertices = 0
        caseIndices = 0
        roomVertices = 0
        roomIndices = 0
    }

    // ---------------------------------------------------------------- input

    /** What a tap did. */
    data class Tap(val slot: Int, val open: Boolean)

    /**
     * A tap lifts a book, opens the one already lifted, or puts everything back.
     * [Tap.slot] is -1 when the tap missed the shelf.
     */
    fun tap(x: Float, y: Float): Tap {
        val pick = ShelfLayout.pickAngle(x, y, viewWidth.toFloat(), viewHeight.toFloat(), rows)
        val slot = pick?.let { ShelfLayout.slotAt(slots, it.first, it.second) }
        if (slot == null) {
            if (selected >= 0) {
                targets[selected] = 0f
                selected = -1
                requestRender()
            }
            return Tap(-1, false)
        }
        if (selected == slot.index) {
            return Tap(slot.index, true) // already out: the caller opens it
        }
        if (selected >= 0) targets[selected] = 0f
        selected = slot.index
        targets[slot.index] = 1f
        requestRender()
        return Tap(slot.index, false)
    }

    /** Index of the lifted book, or -1. */
    fun liftedSlot(): Int = selected

    fun release() {
        stopThread()
    }
}
