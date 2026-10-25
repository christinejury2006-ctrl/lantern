package com.lantern.library.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.lantern.library.data.LibraryBook

/**
 * A few books piled on top of the case, with an apple on top.
 *
 * Purely by way of being furniture: the shelf below is the shelf, and this is the little pile
 * left on the end of it, dropped into place with a spring the way a stack of books actually
 * lands. Tapping a book in the pile opens it, so it is not just decoration.
 *
 * Drawn rather than rendered in 3D on purpose. A stack is three flat boards and a sphere; doing
 * it in Compose costs nothing, works before the engine has started, and cannot be the reason a
 * 3D scene fails to draw.
 */
@Composable
fun ShelfTopStack(
    books: List<LibraryBook>,
    onOpen: (LibraryBook) -> Unit,
    modifier: Modifier = Modifier,
    widthDp: Int = 108
) {
    val pile = remember(books) { books.take(3) }
    if (pile.isEmpty()) return

    // The pile arrives after the shelf, from above, and settles.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(pile.map { it.id }) { landed = true }
    val drop by animateFloatAsState(
        targetValue = if (landed) 0f else -1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pile-drop"
    )

    // Each book is a numbered hit target inside the drawing.
    Box(
        modifier
            .size(widthDp.dp, (widthDp * 0.82f).dp)
            .clickable {
                // Tapping the pile opens the top book; the whole stack is one target so the
                // books stay big enough to see.
                pile.lastOrNull()?.let(onOpen)
            }
    ) {
        val colours = remember(pile) { pile.map { spineColorFor(it.title, it.id) } }
        Canvas(Modifier.size(widthDp.dp, (widthDp * 0.82f).dp)) {
            val w = size.width
            val h = size.height
            // -1 falling in from above, 0 landed
            val settle = drop

            // A soft shadow on the shelf, which fades as the pile arrives.
            drawOval(
                color = Color(0x55000000),
                topLeft = Offset(w * 0.10f, h * 0.76f),
                size = Size(w * 0.80f, h * 0.16f)
            )

            // Three boards, bottom to top, each turned a little so the pile looks stacked by
            // hand rather than by a layout engine.
            val slabs = listOf(
                Triple(0.00f, -4f, 1.00f),
                Triple(0.06f, 3.5f, 0.92f),
                Triple(-0.03f, -2.5f, 0.86f)
            )
            slabs.forEachIndexed { index, (inset, turn, scale) ->
                val colour = colours.getOrElse(index) { 0xFF6B4A2F.toInt() }
                val slabW = w * (0.86f - inset) * scale
                val slabH = h * 0.13f
                val x = (w - slabW) / 2f + inset * w * 0.5f
                val y = h * 0.70f - slabH * index - slabH * 0.55f +
                    settle * (h * 0.30f) * (1f + index * 0.35f)
                rotate(turn, pivot = Offset(x + slabW / 2f, y + slabH / 2f)) {
                    drawBookSlab(x, y, slabW, slabH, colour)
                }
            }

            // The apple: a red sphere with one warm highlight, a stem, and a leaf. Small enough
            // that a wrong curve reads as a different fruit, so it is kept simple.
            val appleR = w * 0.11f
            val cx = w * 0.52f
            val cy = h * 0.30f + settle * (h * 0.22f)
            // stem
            drawLine(
                color = Color(0xFF4A3524),
                start = Offset(cx, cy - appleR * 0.7f),
                end = Offset(cx + appleR * 0.18f, cy - appleR * 1.45f),
                strokeWidth = w * 0.012f
            )
            // leaf
            val leaf = Path().apply {
                moveTo(cx + appleR * 0.18f, cy - appleR * 1.35f)
                quadraticBezierTo(
                    cx + appleR * 0.95f, cy - appleR * 1.75f,
                    cx + appleR * 1.15f, cy - appleR * 1.05f
                )
                quadraticBezierTo(
                    cx + appleR * 0.55f, cy - appleR * 0.95f,
                    cx + appleR * 0.18f, cy - appleR * 1.35f
                )
                close()
            }
            drawPath(leaf, Color(0xFF4F6B3A))
            // body: two lobes, which is what makes it an apple rather than a ball
            drawCircle(Color(0xFF9E2B25), appleR, Offset(cx - appleR * 0.38f, cy + appleR * 0.12f))
            drawCircle(Color(0xFF9E2B25), appleR * 0.94f, Offset(cx + appleR * 0.38f, cy + appleR * 0.12f))
            drawCircle(Color(0xFFB8352C), appleR * 0.74f, Offset(cx, cy + appleR * 0.22f))
            // one highlight, up and to the left, where the light is
            drawCircle(
                Color(0x66FFFFFF),
                appleR * 0.22f,
                Offset(cx - appleR * 0.55f, cy - appleR * 0.20f)
            )
        }
    }
}

/** A closed book seen edge-on: two boards, a page block and a spine. */
private fun DrawScope.drawBookSlab(x: Float, y: Float, w: Float, h: Float, colour: Int) {
    val cover = Color(colour)
    val board = cover.copy(alpha = 0.95f)
    // page block
    drawRect(
        color = Color(0xFFE8DCC2),
        topLeft = Offset(x + h * 0.12f, y + h * 0.12f),
        size = Size(w - h * 0.24f, h * 0.76f)
    )
    // boards, top and bottom
    drawRect(board, Offset(x, y), Size(w, h * 0.20f))
    drawRect(board, Offset(x, y + h * 0.80f), Size(w, h * 0.20f))
    // spine down the left edge
    drawRect(cover, Offset(x, y), Size(h * 0.30f, h))
    // a title band, so a closed book still reads as a book
    drawRect(
        color = Color(0x33FFFFFF),
        topLeft = Offset(x + w * 0.30f, y + h * 0.42f),
        size = Size(w * 0.42f, h * 0.10f)
    )
}
