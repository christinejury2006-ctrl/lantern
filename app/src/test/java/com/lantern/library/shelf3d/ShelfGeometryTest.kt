package com.lantern.library.shelf3d

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tangent-frame encoder, checked against Filament's own decoder.
 *
 * Filament has no normal vertex attribute. A vertex's normal arrives as a quaternion in
 * TANGENTS, and the vertex shader turns it back into a normal with this formula from
 * `shaders/src/common_math.glsl` (v1.72.0):
 *
 *     n = vec3(0,0,1) + vec3(2,-2,-2) * q.x * q.zwx + vec3(2,2,-2) * q.y * q.wzy
 *
 * If the encoder and the decoder disagree, every surface in the scene is lit from the wrong
 * direction — books render black or inside out — and no amount of device testing says why.
 * Running the shader's arithmetic here proves they agree.
 */
class ShelfGeometryTest {

    /** Filament's `toTangentFrame(q, n)`, transcribed exactly. */
    private fun decodeNormal(q: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        // q.zwx = (q.z, q.w, q.x), q.wzy = (q.w, q.z, q.y)
        val zwx = floatArrayOf(qz, qw, qx)
        val wzy = floatArrayOf(qw, qz, qy)
        return floatArrayOf(
            0f + 2f * qx * zwx[0] + 2f * qy * wzy[0],
            0f - 2f * qx * zwx[1] + 2f * qy * wzy[1],
            1f - 2f * qx * zwx[2] - 2f * qy * wzy[2]
        )
    }

    /** Filament's `toTangentFrame(q, n, t)`, for the first column. */
    private fun decodeTangent(q: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        // q.yxw = (q.y, q.x, q.w), q.zwx = (q.z, q.w, q.x)
        val yxw = floatArrayOf(qy, qx, qw)
        val zwx = floatArrayOf(qz, qw, qx)
        return floatArrayOf(
            1f - 2f * qy * yxw[0] - 2f * qz * zwx[0],
            0f + 2f * qy * yxw[1] + 2f * qz * zwx[1],
            0f - 2f * qy * yxw[2] + 2f * qz * zwx[2]
        )
    }

    private fun assertVec(expected: FloatArray, actual: FloatArray, message: String) {
        for (i in 0 until 3) {
            assertEquals("$message (component $i)", expected[i], actual[i], 1e-4f)
        }
    }

    @Test
    fun `identity frame decodes to facing the camera`() {
        // The simplest possible frame: normal +z, tangent +x. Its quaternion is the identity,
        // which is the one case a wrong sign convention cannot hide.
        val q = ShelfGeometry.tangentFrame(0f, 0f, 1f, 1f, 0f, 0f)
        assertVec(floatArrayOf(0f, 0f, 1f), decodeNormal(q), "identity normal")
        assertVec(floatArrayOf(1f, 0f, 0f), decodeTangent(q), "identity tangent")
    }

    @Test
    fun `every box face decodes to the normal it was built with`() {
        // These are the six normals box() actually writes. If any one is wrong, that face of
        // every book in the library is lit wrong.
        val cases = linkedMapOf(
            "spine (+z)" to floatArrayOf(0f, 0f, 1f),
            "fore-edge (-z)" to floatArrayOf(0f, 0f, -1f),
            "right board (+x)" to floatArrayOf(1f, 0f, 0f),
            "left board (-x)" to floatArrayOf(-1f, 0f, 0f),
            "top edge (+y)" to floatArrayOf(0f, 1f, 0f),
            "bottom edge (-y)" to floatArrayOf(0f, -1f, 0f)
        )
        for ((label, n) in cases) {
            val nx = n[0]
            val ny = n[1]
            val nz = n[2]
            // any in-plane hint; each is perpendicular to its own normal
            val hint = when {
                nz != 0f -> floatArrayOf(1f, 0f, 0f)
                nx != 0f -> floatArrayOf(0f, 0f, 1f)
                else -> floatArrayOf(1f, 0f, 0f)
            }
            val q = ShelfGeometry.tangentFrame(nx, ny, nz, hint[0], hint[1], hint[2])
            assertVec(n, decodeNormal(q), "$label normal")
            assertVec(hint, decodeTangent(q), "$label tangent")
        }
    }

    @Test
    fun `frames are orthonormal for any direction around the drum`() {
        // Every book is rotated to its own angle, so the encoder must work all the way round,
        // not just for the axis-aligned faces.
        var angle = 0f
        while (angle < 6.28f) {
            val n = floatArrayOf(kotlin.math.sin(angle), 0f, kotlin.math.cos(angle))
            val hint = floatArrayOf(kotlin.math.cos(angle), 0f, -kotlin.math.sin(angle))
            val q = ShelfGeometry.tangentFrame(n[0], n[1], n[2], hint[0], hint[1], hint[2])
            val dn = decodeNormal(q)
            val dt = decodeTangent(q)

            assertVec(n, dn, "normal at $angle")
            assertVec(hint, dt, "tangent at $angle")
            // unit length
            assertEquals(1f, sqrt(dn[0] * dn[0] + dn[1] * dn[1] + dn[2] * dn[2]), 1e-4f)
            assertEquals(1f, sqrt(dt[0] * dt[0] + dt[1] * dt[1] + dt[2] * dt[2]), 1e-4f)
            // perpendicular
            assertEquals(0f, dn[0] * dt[0] + dn[1] * dt[1] + dn[2] * dt[2], 1e-4f)
            // a unit quaternion
            val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            assertEquals(1f, len, 1e-4f)
            angle += 0.21f
        }
    }

    @Test
    fun `a hint parallel to the normal does not produce a degenerate frame`() {
        // A caller passing a bad hint must not yield zeros, which would decode as a broken
        // normal and light the face black.
        val q = ShelfGeometry.tangentFrame(0f, 1f, 0f, 0f, 1f, 0f)
        val n = decodeNormal(q)
        val t = decodeTangent(q)
        assertVec(floatArrayOf(0f, 1f, 0f), n, "recovers the normal anyway")
        assertEquals(1f, sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]), 1e-4f)
        assertEquals(0f, n[0] * t[0] + n[1] * t[1] + n[2] * t[2], 1e-4f)
    }

    @Test
    fun `the box is watertight and has hard edges`() {
        val mesh = ShelfGeometry.box()
        assertEquals("6 faces, 4 vertices each, no sharing", 24, mesh.vertexCount)
        assertEquals(36, mesh.indexCount)
        // every index in range
        assertTrue(mesh.indices.all { it >= 0 && it < mesh.vertexCount })
        // every vertex has the full stride and a unit tangent quaternion
        for (v in 0 until mesh.vertexCount) {
            val base = v * ShelfGeometry.FLOATS_PER_VERTEX
            val q = floatArrayOf(
                mesh.vertices[base + 3], mesh.vertices[base + 4],
                mesh.vertices[base + 5], mesh.vertices[base + 6]
            )
            assertEquals(
                1f,
                sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]),
                1e-3f
            )
        }
    }

    @Test
    fun `the carcass sits around the drum and hides what is past its horizon`() {
        val mesh = ShelfGeometry.backboard(
            radius = CylinderMath.DRUM_RADIUS - CylinderMath.BOOK_DEPTH * 0.5f - 0.008f,
            yBottom = -0.6f,
            yTop = 1.4f
        )
        assertEquals(0, mesh.vertexCount % 2)
        for (v in 0 until mesh.vertexCount) {
            val base = v * ShelfGeometry.FLOATS_PER_VERTEX
            val x = mesh.vertices[base]
            val y = mesh.vertices[base + 1]
            val z = mesh.vertices[base + 2]
            // same radius everywhere, so the wall is a true cylinder
            val r = sqrt(x * x + (z + CylinderMath.AXIS_DISTANCE) * (z + CylinderMath.AXIS_DISTANCE))
            // the wall stands just behind the backs of the books
            assertEquals(CylinderMath.DRUM_RADIUS - CylinderMath.BOOK_DEPTH / 2f - 0.008f, r, 1e-3f)
            // and it stands behind the books, never in front of them
            assertTrue("wall pokes through the shelf at z=$z", z < 0f)
            assertTrue(y == -0.6f || y == 1.4f)
        }
    }

    @Test
    fun `the floor lies flat under the room`() {
        val y = CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f - 0.035f
        val mesh = ShelfGeometry.floor(centreZ = -CylinderMath.AXIS_DISTANCE, y = y, radius = 2.5f)
        assertTrue(mesh.vertexCount > 2)
        for (v in 0 until mesh.vertexCount) {
            val base = v * ShelfGeometry.FLOATS_PER_VERTEX
            assertEquals("the floor must be level", y, mesh.vertices[base + 1], 1e-6f)
            val n = decodeNormal(
                floatArrayOf(
                    mesh.vertices[base + 3], mesh.vertices[base + 4],
                    mesh.vertices[base + 5], mesh.vertices[base + 6]
                )
            )
            assertVec(floatArrayOf(0f, 1f, 0f), n, "the floor faces up")
        }
        // and it sits below the board the books stand on
        assertTrue(y < CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f)
    }

    @Test
    fun `the plinth is a full ring just under the books`() {
        val topY = CylinderMath.BOOK_Y - CylinderMath.BOOK_HEIGHT / 2f
        val mesh = ShelfGeometry.plinth(radius = CylinderMath.DRUM_RADIUS, topY = topY, thickness = 0.024f)
        assertTrue(mesh.vertexCount > 0)
        for (v in 0 until mesh.vertexCount) {
            val base = v * ShelfGeometry.FLOATS_PER_VERTEX
            // every plinth vertex is at the board's top height and faces up
            assertEquals(topY, mesh.vertices[base + 1], 1e-6f)
            val n = decodeNormal(
                floatArrayOf(
                    mesh.vertices[base + 3], mesh.vertices[base + 4],
                    mesh.vertices[base + 5], mesh.vertices[base + 6]
                )
            )
            assertVec(floatArrayOf(0f, 1f, 0f), n, "plinth face points up")
            val x = mesh.vertices[base]
            val z = mesh.vertices[base + 2]
            val r = sqrt(x * x + (z + CylinderMath.AXIS_DISTANCE) * (z + CylinderMath.AXIS_DISTANCE))
            // the board runs from just past the drum's face to a book's depth behind it
            assertTrue("plinth radius $r is off the drum", r <= CylinderMath.DRUM_RADIUS + 0.02f)
            assertTrue("plinth radius $r is not deep enough", r >= CylinderMath.DRUM_RADIUS - 0.21f)
        }
    }
}
