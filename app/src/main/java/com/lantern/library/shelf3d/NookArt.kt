package com.lantern.library.shelf3d

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader

/**
 * A niche set into the case: a lit alcove standing where the gap between the picks and the rest
 * of the library would otherwise be an empty slot.
 *
 * Painted, like the spines, into the same atlas. That matters for a reason beyond convenience:
 * a painted tile cannot fail to compile, cannot be rejected by a driver, and cannot stop the
 * shelf from starting. The scene only has to know where the tile is.
 *
 * The painting is deliberately simple — an arch, a lamp, a vine, a few books on the floor of the
 * niche. At the size a phone shows these things, a handful of shapes with warm light does more
 * than detail would.
 */
object NookArt {

    /** Paints the niche into [rect]. */
    fun paint(canvas: Canvas, rect: Rect) {
        val left = rect.left.toFloat()
        val top = rect.top.toFloat()
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()

        // The inside of the niche: near black, so the light has something to fall on.
        val dark = Paint().apply { color = 0xFF140D08.toInt() }
        canvas.drawRect(rect, dark)

        // A wooden arch, with the opening cut out of it.
        val arch = Path().apply {
            moveTo(left, top + h)
            lineTo(left, top + h * 0.22f)
            cubicTo(left, top + h * 0.04f, left + w, top + h * 0.04f, left + w, top + h * 0.22f)
            lineTo(left + w, top + h)
            close()
        }
        canvas.drawPath(arch, Paint().apply {
            color = 0xFF3A2417.toInt()
            style = Paint.Style.STROKE
            strokeWidth = w * 0.16f
        })

        // The lamp's glow, falling down the niche.
        val lampX = left + w * 0.5f
        val lampY = top + h * 0.30f
        val glow = Paint().apply {
            shader = RadialGradient(
                lampX, lampY, w * 1.5f,
                intArrayOf(0x99FFC978.toInt(), 0x337A4C1E.toInt(), 0x00000000),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, glow)

        // The lamp itself: a hung lantern.
        canvas.drawLine(lampX, top + h * 0.10f, lampX, lampY - w * 0.14f, Paint().apply {
            color = 0xFF2A1A10.toInt()
            strokeWidth = w * 0.03f
        })
        canvas.drawRect(
            lampX - w * 0.10f, lampY - w * 0.14f, lampX + w * 0.10f, lampY + w * 0.10f,
            Paint().apply { color = 0xFFFFD98A.toInt() }
        )
        canvas.drawRect(
            lampX - w * 0.14f, lampY - w * 0.18f, lampX + w * 0.14f, lampY - w * 0.14f,
            Paint().apply { color = 0xFF3A2417.toInt() }
        )

        // The floor of the niche, and a few little books left on it.
        val shelfY = top + h * 0.78f
        canvas.drawRect(left, shelfY, left + w, shelfY + h * 0.02f, Paint().apply {
            color = 0xFF4A2E1C.toInt()
        })
        val spines = listOf(
            0xFF7A2E2A.toInt() to 0.18f,
            0xFF2F4A52.toInt() to 0.38f,
            0xFF6B5A2E.toInt() to 0.55f
        )
        for ((colour, at) in spines) {
            val x = left + w * at
            canvas.drawRect(
                x, shelfY - h * 0.09f, x + w * 0.12f, shelfY,
                Paint().apply { this.color = colour }
            )
        }

        // A vine down one side, with leaves. The shelf in the reference is growing, not tidy.
        val vine = Path().apply {
            moveTo(left + w * 0.14f, top + h * 0.05f)
            cubicTo(
                left + w * 0.05f, top + h * 0.35f,
                left + w * 0.26f, top + h * 0.55f,
                left + w * 0.15f, top + h * 0.80f
            )
        }
        canvas.drawPath(vine, Paint().apply {
            color = 0xFF3F5A33.toInt()
            style = Paint.Style.STROKE
            strokeWidth = w * 0.05f
        })
        val leaf = Paint().apply { color = 0xFF557A42.toInt() }
        for (at in listOf(0.24f, 0.44f, 0.66f)) {
            canvas.drawOval(
                left + w * 0.16f, top + h * at,
                left + w * 0.40f, top + h * (at + 0.05f),
                leaf
            )
        }

        // A mushroom on the floor, glowing faintly, because bookshelves in stories have them.
        canvas.drawCircle(
            left + w * 0.74f, shelfY - h * 0.05f, w * 0.10f,
            Paint().apply { color = 0xFF7FD3C0.toInt() }
        )
        canvas.drawRect(
            left + w * 0.72f, shelfY - h * 0.05f, left + w * 0.76f, shelfY,
            Paint().apply { color = 0xFFE8DCC2.toInt() }
        )
    }
}
