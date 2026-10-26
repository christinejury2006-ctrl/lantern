package com.lantern.library.shelf3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wood of the case: one grain texture, shared by the boards, the wall and the floor.
 *
 * A flat brown PBR surface under a single sun is the reason the case looked like brown paper
 * rather than woodwork. Grain is what carries "wood": long figure running along the board, a few
 * knots, and a darker edge where two boards meet. It costs one texture, painted once, and the
 * material is the same one the carcass already uses.
 *
 * Painted rather than loaded from an asset so the case never depends on a file that might not be
 * there, and tinted per part by the instance's base colour, so one texture serves three woods.
 */
object ShelfWood {

    /** Grain runs along the texture's x axis. */
    private const val SIZE = 256

    fun bitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Base: a warm mid-brown. Instance colours multiply into this, so it is kept neutral.
        canvas.drawRect(
            0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, SIZE.toFloat(),
                    Color.rgb(168, 132, 96), Color.rgb(126, 94, 66),
                    Shader.TileMode.CLAMP
                )
            }
        )

        val random = Random(7)
        // Long figure: shallow sine waves running the length of the board.
        val line = Paint().apply { strokeWidth = 1.4f }
        for (i in 0 until 90) {
            val y = random.nextFloat() * SIZE
            val amplitude = 1.5f + random.nextFloat() * 4f
            val period = 60f + random.nextFloat() * 140f
            val phase = random.nextFloat() * 6.28f
            line.color = if (random.nextBoolean()) {
                Color.argb(28 + random.nextInt(30), 70, 44, 26)
            } else {
                Color.argb(22 + random.nextInt(24), 226, 198, 160)
            }
            var x = 0f
            while (x < SIZE) {
                val y0 = y + sin(phase + x / period) * amplitude
                val y1 = y + sin(phase + (x + 8f) / period) * amplitude
                canvas.drawLine(x, y0, x + 8f, y1, line)
                x += 8f
            }
        }

        // Knots: a dark eye with rings around it. Two is enough; more looks like a disease.
        repeat(2) {
            val cx = 40f + random.nextFloat() * (SIZE - 80f)
            val cy = 40f + random.nextFloat() * (SIZE - 80f)
            for (ring in 6 downTo 1) {
                val radius = ring * 5.5f
                canvas.drawCircle(
                    cx, cy, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 1.2f
                        color = Color.argb(70, 62, 38, 22)
                    }
                )
            }
            canvas.drawCircle(cx, cy, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, 40, 22, 12)
            })
        }

        // A darker band at the edge of each board, which is what a panel looks like under varnish.
        canvas.drawRect(0f, 0f, SIZE.toFloat(), 3f, Paint().apply {
            color = Color.argb(60, 40, 22, 12)
        })
        canvas.drawRect(0f, SIZE - 3f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply {
            color = Color.argb(60, 40, 22, 12)
        })

        return bitmap
    }

    /** A small mid-brown used for parts that should not compete with the boards. */
    const val NEUTRAL = 0xFF9A7A55.toInt()
}
