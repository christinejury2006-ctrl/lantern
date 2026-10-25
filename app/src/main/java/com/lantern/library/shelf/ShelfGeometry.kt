package com.lantern.library.shelf

import kotlin.math.cos

/**
 * Builds the meshes the shelf is drawn from.
 *
 * Everything is unlit and carries its own shading in the vertex colours, so no surface here
 * depends on tangents, shadow maps or image-based lighting to be visible. That is what keeps
 * the scene free of the "black rectangle" failure modes a lit pipeline has on a strange GPU.
 *
 * Vertex layout: position (3 floats), uv (2 floats), colour (4 floats) = 36 bytes.
 */
object ShelfGeometry {

    const val STRIDE_BYTES = 36
    const val POSITION_OFFSET = 0
    const val UV_OFFSET = 12
    const val COLOR_OFFSET = 20

    class Mesh(val vertices: FloatArray, val indices: ShortArray) {
        val vertexCount: Int get() = vertices.size / 9
        val indexCount: Int get() = indices.size
    }

    private class Builder {
        private val v = ArrayList<Float>(1024)
        private val i = ArrayList<Short>(2048)
        private var next = 0

        fun vertex(x: Float, y: Float, z: Float, u: Float, w: Float, shade: Float) {
            v.add(x); v.add(y); v.add(z)
            v.add(u); v.add(w)
            v.add(shade); v.add(shade); v.add(shade); v.add(1f)
            next++
        }

        fun quad(
            ax: Float, ay: Float, az: Float, au: Float, av: Float,
            bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
            cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
            dx: Float, dy: Float, dz: Float, du: Float, dv: Float,
            region: Region,
            shade: Float
        ) {
            val base = next
            val (axx, ayv) = region.map(au, av)
            vertex(ax, ay, az, axx, ayv, shade)
            val (bxx, byv) = region.map(bu, bv)
            vertex(bx, by, bz, bxx, byv, shade)
            val (cxx, cyv) = region.map(cu, cv)
            vertex(cx, cy, cz, cxx, cyv, shade)
            val (dxx, dyv) = region.map(du, dv)
            vertex(dx, dy, dz, dxx, dyv, shade)
            i.add((base + 0).toShort()); i.add((base + 1).toShort()); i.add((base + 2).toShort())
            i.add((base + 0).toShort()); i.add((base + 2).toShort()); i.add((base + 3).toShort())
        }

        val vertexCount: Int get() = next

        fun tri(a: Int, b: Int, c: Int) {
            i.add(a.toShort()); i.add(b.toShort()); i.add(c.toShort())
        }

        fun build(): Mesh {
            val verts = FloatArray(v.size)
            for (n in v.indices) verts[n] = v[n]
            val idx = ShortArray(i.size)
            for (n in i.indices) idx[n] = i[n]
            return Mesh(verts, idx)
        }
    }

    /** Where inside an atlas cell a face's uv lands. */
    enum class Region {
        SPINE {
            override fun map(u: Float, v: Float): Pair<Float, Float> =
                Pair(u * ShelfLayout.SPINE_U1, v)
        },
        COVER {
            override fun map(u: Float, v: Float): Pair<Float, Float> =
                Pair(ShelfLayout.SPINE_U1 + u * (1f - ShelfLayout.SPINE_U1), v * ShelfLayout.COVER_V1)
        },
        PAGES {
            override fun map(u: Float, v: Float): Pair<Float, Float> =
                Pair(
                    ShelfLayout.SPINE_U1 + u * (1f - ShelfLayout.SPINE_U1),
                    ShelfLayout.COVER_V1 + v * (1f - ShelfLayout.COVER_V1)
                )
        };

        abstract fun map(u: Float, v: Float): Pair<Float, Float>
    }

    // How much of the room's light each face of a book catches. The front (spine) faces the
    // viewer, the top catches the most, the underside the least: the same trick the flat
    // shelf used, baked into the mesh so the frame loop does no lighting work.
    private const val SHADE_SPINE = 1.0f
    private const val SHADE_TOP = 0.94f
    private const val SHADE_BOTTOM = 0.58f
    private const val SHADE_COVER = 0.76f
    private const val SHADE_FORE_EDGE = 0.70f

    /**
     * A unit box centred on the origin. Scaled per book by its transform, so a shelf of any
     * number of books shares this one mesh and one draw call each.
     *
     * The spine face's u runs backwards on purpose: a book that faces the viewer is turned
     * by (PI - angle), which leaves its local +x pointing at screen-left, so mirroring u here
     * is what stops every title reading back to front.
     */
    fun box(): Mesh {
        val b = Builder()
        // spine, -z
        b.quad(
            -0.5f, 0.5f, -0.5f, 1f, 0f,
            0.5f, 0.5f, -0.5f, 0f, 0f,
            0.5f, -0.5f, -0.5f, 0f, 1f,
            -0.5f, -0.5f, -0.5f, 1f, 1f,
            Region.SPINE, SHADE_SPINE
        )
        // fore-edge, +z
        b.quad(
            -0.5f, 0.5f, 0.5f, 0f, 0f,
            0.5f, 0.5f, 0.5f, 1f, 0f,
            0.5f, -0.5f, 0.5f, 1f, 1f,
            -0.5f, -0.5f, 0.5f, 0f, 1f,
            Region.PAGES, SHADE_FORE_EDGE
        )
        // top, +y
        b.quad(
            -0.5f, 0.5f, -0.5f, 0f, 0f,
            0.5f, 0.5f, -0.5f, 1f, 0f,
            0.5f, 0.5f, 0.5f, 1f, 1f,
            -0.5f, 0.5f, 0.5f, 0f, 1f,
            Region.PAGES, SHADE_TOP
        )
        // bottom, -y
        b.quad(
            -0.5f, -0.5f, 0.5f, 0f, 0f,
            0.5f, -0.5f, 0.5f, 1f, 0f,
            0.5f, -0.5f, -0.5f, 1f, 1f,
            -0.5f, -0.5f, -0.5f, 0f, 1f,
            Region.PAGES, SHADE_BOTTOM
        )
        // left cover, -x
        b.quad(
            -0.5f, 0.5f, -0.5f, 0f, 0f,
            -0.5f, 0.5f, 0.5f, 1f, 0f,
            -0.5f, -0.5f, 0.5f, 1f, 1f,
            -0.5f, -0.5f, -0.5f, 0f, 1f,
            Region.COVER, SHADE_COVER
        )
        // right cover, +x
        b.quad(
            0.5f, 0.5f, 0.5f, 0f, 0f,
            0.5f, 0.5f, -0.5f, 1f, 0f,
            0.5f, -0.5f, -0.5f, 1f, 1f,
            0.5f, -0.5f, 0.5f, 0f, 1f,
            Region.COVER, SHADE_COVER
        )
        return b.build()
    }

    /**
     * The case: every strip in [ShelfLayout.caseStrips], swept around the arc. Shading falls
     * off toward the edges of the arc, which is what sells the curve without any lights.
     */
    fun caseMesh(segments: Int = 24): Mesh {
        val b = Builder()
        val list = ShelfLayout.caseStrips()
        for (strip in list) {
            val base = b.vertexCount
            for (s in 0..segments) {
                val t = s.toFloat() / segments
                val a = strip.a0 + (strip.a1 - strip.a0) * t
                val x = ShelfLayout.arcX(a, strip.radius)
                val z = ShelfLayout.arcZ(a, strip.radius)
                val curve = 0.84f + 0.16f * cos(a).coerceAtLeast(0f)
                b.vertex(x, strip.y1, z, t, 0f, strip.shade * curve)
                b.vertex(x, strip.y0, z, t, 1f, strip.shade * curve * 0.92f)
            }
            for (s in 0 until segments) {
                val a0 = base + s * 2
                val a1 = a0 + 1
                val a2 = a0 + 2
                val a3 = a0 + 3
                b.tri(a0, a1, a2)
                b.tri(a1, a3, a2)
            }
        }
        return b.build()
    }

    /**
     * The painted room standing behind the case: one wide arc, bright, so the shelf sits in
     * a lit interior instead of in blackness.
     */
    fun roomMesh(segments: Int = 24): Mesh {
        val b = Builder()
        // Deliberately much larger than the case: the painted room has to cover the whole
        // window whatever shape it is, or the scene shows black bars above and below it.
        val a0 = -ShelfLayout.HALF_ARC - 0.70f
        val a1 = ShelfLayout.HALF_ARC + 0.70f
        val top = ShelfLayout.caseTopY() + 2.30f
        val bottom = ShelfLayout.caseBottomY() - 2.60f
        val base = 0
        for (s in 0..segments) {
            val t = s.toFloat() / segments
            val a = a0 + (a1 - a0) * t
            val x = ShelfLayout.arcX(a, ShelfLayout.ROOM_RADIUS)
            val z = ShelfLayout.arcZ(a, ShelfLayout.ROOM_RADIUS)
            b.vertex(x, top, z, t, 0f, 1f)
            b.vertex(x, bottom, z, t, 1f, 0.78f)
        }
        for (s in 0 until segments) {
            val i0 = base + s * 2
            b.tri(i0, i0 + 1, i0 + 2)
            b.tri(i0 + 1, i0 + 3, i0 + 2)
        }
        return b.build()
    }
}
