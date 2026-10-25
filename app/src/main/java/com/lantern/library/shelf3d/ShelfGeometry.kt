package com.lantern.library.shelf3d

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Meshes for the bookcase, in the layout Filament expects.
 *
 * The surprise here is the tangent frame. **Filament has no normal attribute.** Each vertex
 * carries `TANGENTS`, four floats holding a quaternion that encodes the whole tangent frame.
 * The vertex shader decodes it with (`shaders/src/common_math.glsl`, v1.72.0):
 *
 *     n = vec3(0,0,1) + vec3(2,-2,-2) * q.x * q.zwx + vec3(2,2,-2) * q.y * q.wzy
 *
 * which is exactly the third column of the rotation matrix for `q`. Since the first column is
 * the tangent and the second the bitangent, the frame must be encoded as the rotation whose
 * columns are (tangent, bitangent, normal) — with `bitangent = normal × tangent` to keep the
 * basis right-handed. Encode anything else, such as the popular "tangent xyz plus a sign"
 * layout from other engines, and every surface is lit as if its normals pointed somewhere
 * else: books come out black or inside-out. [ShelfGeometryTest] runs the shader's own formula
 * to prove the encoder round-trips.
 *
 * Vertex layout: position (3) + tangent quaternion (4) + uv (2) = 9 floats = 36 bytes.
 */
object ShelfGeometry {

    const val STRIDE_BYTES = 36
    const val POSITION_OFFSET = 0
    const val TANGENT_OFFSET = 12
    const val UV_OFFSET = 28

    /** Floats per vertex in [Mesh.vertices]. */
    const val FLOATS_PER_VERTEX = 9

    class Mesh(val vertices: FloatArray, val indices: ShortArray) {
        val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
        val indexCount: Int get() = indices.size
    }

    /**
     * Encodes an orthonormal tangent frame as Filament's quaternion, given the surface normal
     * and any in-plane direction.
     *
     * The hint is orthogonalised against the normal rather than trusted, because callers
     * compute it by hand and a few degrees of error would skew the frame.
     */
    fun tangentFrame(
        nx: Float, ny: Float, nz: Float,
        hintX: Float, hintY: Float, hintZ: Float
    ): FloatArray {
        // normalise the normal
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        val ux = nx / nl
        val uy = ny / nl
        val uz = nz / nl

        // Gram-Schmidt the hint against the normal, so tangent ⟂ normal exactly
        val d = hintX * ux + hintY * uy + hintZ * uz
        var tx = hintX - ux * d
        var ty = hintY - uy * d
        var tz = hintZ - uz * d
        var tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-6f) {
            // The hint was parallel to the normal, so nothing of it survives. Cross the normal
            // with whichever axis it is least aligned to: that cross product cannot be zero,
            // whereas any fixed choice of axis can be.
            when {
                abs(ux) <= abs(uy) && abs(ux) <= abs(uz) -> {
                    // e = x, so n x e = (0, uz, -uy)
                    tx = 0f; ty = uz; tz = -uy
                }
                abs(uy) <= abs(uz) -> {
                    // e = y, so n x e = (-uz, 0, ux)
                    tx = -uz; ty = 0f; tz = ux
                }
                else -> {
                    // e = z, so n x e = (uy, -ux, 0)
                    tx = uy; ty = -ux; tz = 0f
                }
            }
            tl = sqrt(tx * tx + ty * ty + tz * tz)
        }
        tx /= tl; ty /= tl; tz /= tl

        // bitangent = normal × tangent, so (t, b, n) is right-handed
        val bx = uy * tz - uz * ty
        val by = uz * tx - ux * tz
        val bz = ux * ty - uy * tx

        // Rotation matrix with columns t, b, n, converted to a quaternion (Shepperd's method).
        val m00 = tx; val m01 = bx; val m02 = ux
        val m10 = ty; val m11 = by; val m12 = uy
        val m20 = tz; val m21 = bz; val m22 = uz

        val trace = m00 + m11 + m22
        var qw: Float
        var qx: Float
        var qy: Float
        var qz: Float
        when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                qw = 0.25f * s
                qx = (m21 - m12) / s
                qy = (m02 - m20) / s
                qz = (m10 - m01) / s
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                qw = (m21 - m12) / s
                qx = 0.25f * s
                qy = (m01 + m10) / s
                qz = (m02 + m20) / s
            }
            m11 > m22 -> {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                qw = (m02 - m20) / s
                qx = (m01 + m10) / s
                qy = 0.25f * s
                qz = (m12 + m21) / s
            }
            else -> {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                qw = (m10 - m01) / s
                qx = (m02 + m20) / s
                qy = (m12 + m21) / s
                qz = 0.25f * s
            }
        }
        // Keep the scalar part positive: q and -q are the same rotation, but a consistent sign
        // keeps the encoded values stable between neighbouring vertices.
        if (qw < 0f) {
            qw = -qw; qx = -qx; qy = -qy; qz = -qz
        }
        return floatArrayOf(qx, qy, qz, qw)
    }

    private class Builder {
        val v = ArrayList<Float>(FLOATS_PER_VERTEX * 64)
        val i = ArrayList<Short>(36 * 8)

        /** Adds one vertex with its frame encoded from [normal] and [tangentHint]. */
        fun vertex(
            x: Float, y: Float, z: Float,
            u: Float, w: Float,
            normal: FloatArray,
            tangentHint: FloatArray
        ) {
            val q = tangentFrame(normal[0], normal[1], normal[2],
                tangentHint[0], tangentHint[1], tangentHint[2])
            v.add(x); v.add(y); v.add(z)
            v.add(q[0]); v.add(q[1]); v.add(q[2]); v.add(q[3])
            v.add(u); v.add(w)
        }

        /**
         * Adds a quad. Corners are given anticlockwise as seen from the front, with their uvs.
         * [normal] is the face normal and [tangent] its in-plane reference direction.
         */
        fun quad(corners: FloatArray, uvs: FloatArray, normal: FloatArray, tangent: FloatArray) {
            val base = v.size / FLOATS_PER_VERTEX
            for (c in 0 until 4) {
                vertex(
                    corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2],
                    uvs[c * 2], uvs[c * 2 + 1],
                    normal, tangent
                )
            }
            i.add((base + 0).toShort()); i.add((base + 1).toShort()); i.add((base + 2).toShort())
            i.add((base + 0).toShort()); i.add((base + 2).toShort()); i.add((base + 3).toShort())
        }

        fun build(): Mesh {
            val verts = FloatArray(v.size)
            for (n in v.indices) verts[n] = v[n]
            val idx = ShortArray(i.size)
            for (n in i.indices) idx[n] = i[n]
            return Mesh(verts, idx)
        }
    }

    /**
     * A unit box centred on the origin, one metre on each side, so a book's transform scales it
     * to size. Six faces, 24 vertices, hard edges for crisp corners.
     *
     * The spine is the +z face, matching [CylinderMath.facingDegrees].
     */
    fun box(): Mesh {
        val b = Builder()
        val zUp = floatArrayOf(0f, 0f, 1f)
        val zDown = floatArrayOf(0f, 0f, -1f)
        val xUp = floatArrayOf(1f, 0f, 0f)
        val xDown = floatArrayOf(-1f, 0f, 0f)
        val yUp = floatArrayOf(0f, 1f, 0f)
        val yDown = floatArrayOf(0f, -1f, 0f)

        // +z: the spine, and the only face that is normally seen
        b.quad(
            floatArrayOf(-0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            zUp, xUp
        )
        // -z: the fore-edge
        b.quad(
            floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            zDown, xDown
        )
        // +x: right board
        b.quad(
            floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            xUp, zUp
        )
        // -x: left board
        b.quad(
            floatArrayOf(-0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            xDown, zDown
        )
        // +y: top edge
        b.quad(
            floatArrayOf(-0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            yUp, xUp
        )
        // -y: bottom edge
        b.quad(
            floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f),
            floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f),
            yDown, xUp
        )
        return b.build()
    }

    /**
     * The shelf board the books stand on: a flat ring following the drum, [thickness] deep,
     * with its top face at [topY].
     */
    fun plinth(
        radius: Float,
        topY: Float,
        thickness: Float,
        segments: Int = 96,
        axisDistance: Float = CylinderMath.AXIS_DISTANCE
    ): Mesh {
        val v = ArrayList<Float>(FLOATS_PER_VERTEX * segments * 2)
        val idx = ArrayList<Short>(segments * 6)
        val up = floatArrayOf(0f, 1f, 0f)
        for (s in 0..segments) {
            val t = s.toFloat() / segments
            val a = t * (Math.PI * 2).toFloat()
            val sin = kotlin.math.sin(a)
            val cos = kotlin.math.cos(a)
            // tangent along the ring: perpendicular to both up and the radius
            val hint = floatArrayOf(cos, 0f, -sin)
            val u = t * radius * (Math.PI * 2).toFloat() / 0.4f
            for ((r, w) in listOf(radius to 0f, radius - thickness to 1f)) {
                val x = r * sin
                val z = -axisDistance + r * cos
                val q = tangentFrame(up[0], up[1], up[2], hint[0], hint[1], hint[2])
                v.add(x); v.add(topY); v.add(z)
                v.add(q[0]); v.add(q[1]); v.add(q[2]); v.add(q[3])
                v.add(u); v.add(w)
            }
        }
        for (s in 0 until segments) {
            val b0 = (s * 2).toShort()
            idx.add(b0); idx.add((b0 + 1).toShort()); idx.add((b0 + 2).toShort())
            idx.add((b0 + 1).toShort()); idx.add((b0 + 3).toShort()); idx.add((b0 + 2).toShort())
        }
        return Mesh(v.toFloatArray(), idx.toShortArray())
    }

    /**
     * The room's floor: a disc centred under the drum, facing up.
     *
     * Only the near part is ever visible — the carcass wall hides the rest — but drawing the
     * whole disc means the floor never ends at a visible edge, however the phone is held.
     */
    fun floor(centreZ: Float, y: Float, radius: Float, segments: Int = 64): Mesh {
        val v = ArrayList<Float>(FLOATS_PER_VERTEX * (segments + 2))
        val idx = ArrayList<Short>(segments * 3)
        val up = floatArrayOf(0f, 1f, 0f)
        val q = tangentFrame(up[0], up[1], up[2], 1f, 0f, 0f)
        // centre vertex
        v.add(0f); v.add(y); v.add(centreZ)
        v.add(q[0]); v.add(q[1]); v.add(q[2]); v.add(q[3])
        v.add(radius / 0.9f); v.add(radius / 0.9f)
        for (s in 0..segments) {
            val a = s.toFloat() / segments * (Math.PI * 2).toFloat()
            val x = radius * kotlin.math.sin(a)
            val z = centreZ + radius * kotlin.math.cos(a)
            v.add(x); v.add(y); v.add(z)
            v.add(q[0]); v.add(q[1]); v.add(q[2]); v.add(q[3])
            // uv in metres, so a texture would tile at a constant real-world size
            v.add(x / 0.9f); v.add(z / 0.9f)
        }
        for (s in 1..segments) {
            idx.add(0); idx.add((s).toShort()); idx.add((s + 1).toShort())
        }
        return Mesh(v.toFloatArray(), idx.toShortArray())
    }

    /**
     * The carcass wall: a vertical band all the way round the drum at [radius], from [yBottom]
     * to [yTop].
     *
     * Normals point outward, towards the books and the reader. The material draws both faces,
     * so the same shell looks solid from inside while the drum turns, and it is what hides the
     * books that have turned past the drum's horizon.
     */
    fun backboard(
        radius: Float,
        yBottom: Float,
        yTop: Float,
        segments: Int = 96,
        axisDistance: Float = CylinderMath.AXIS_DISTANCE
    ): Mesh {
        val v = ArrayList<Float>(FLOATS_PER_VERTEX * segments * 2)
        val idx = ArrayList<Short>(segments * 6)
        for (s in 0..segments) {
            val t = s.toFloat() / segments
            val a = t * (Math.PI * 2).toFloat()
            val sin = kotlin.math.sin(a)
            val cos = kotlin.math.cos(a)
            // outward normal (sin, 0, cos); tangent runs along the wall's length
            val q = tangentFrame(sin, 0f, cos, 1f, 0f, 0f)
            val u = t * radius * (Math.PI * 2).toFloat() / 0.4f
            for ((y, w) in listOf(yTop to 0f, yBottom to 1f)) {
                v.add(radius * sin); v.add(y); v.add(-axisDistance + radius * cos)
                v.add(q[0]); v.add(q[1]); v.add(q[2]); v.add(q[3])
                v.add(u); v.add(w)
            }
        }
        for (s in 0 until segments) {
            val b0 = (s * 2).toShort()
            idx.add(b0); idx.add((b0 + 1).toShort()); idx.add((b0 + 2).toShort())
            idx.add((b0 + 1).toShort()); idx.add((b0 + 3).toShort()); idx.add((b0 + 2).toShort())
        }
        return Mesh(v.toFloatArray(), idx.toShortArray())
    }
}
