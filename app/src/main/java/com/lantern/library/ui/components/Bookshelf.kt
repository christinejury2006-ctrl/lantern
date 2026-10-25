package com.lantern.library.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.ShelfMath
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Playfair

/** One book as it appears on the shelf. Primitives only, so discovery books fit too. */
data class Spine(
    val id: String,
    val title: String,
    val author: String,
    val pages: Int = 240,
    val progress: Float = 0f
)

/** Warm, low-contrast wood and wall tones. Shared by every shelf surface. */
private object Wood {
    val wallLightTop = Color(0xFFF3E7D6)
    val wallLightBottom = Color(0xFFDFCDB2)
    val wallDarkTop = Color(0xFF251B14)
    val wallDarkBottom = Color(0xFF15100B)
    val boardLight = Color(0xFF9A6E46)
    val boardMid = Color(0xFF6B472C)
    val boardDeep = Color(0xFF3A2418)
    val boardDark = Color(0xFF5E4029)
    val boardDarkMid = Color(0xFF3B2718)
    val boardDarkDeep = Color(0xFF1D130C)
    val gold = Color(0xFFC8A96B)
    val grain = Color(0xFF2A1A10)
}

/**
 * The room the shelves live in: a warm plaster wall with a soft lamp glow and a gentle
 * vignette. Deliberately quiet so the books carry the colour.
 */
@Composable
fun ShelfRoom(
    dark: Boolean,
    modifier: Modifier = Modifier,
    wallAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val a = if (wallAlpha.isFinite()) wallAlpha.coerceIn(0f, 1f) else 1f
    val top = (if (dark) Wood.wallDarkTop else Wood.wallLightTop).copy(alpha = a)
    val bottom = (if (dark) Wood.wallDarkBottom else Wood.wallLightBottom).copy(alpha = a)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            // lamp glow from the top left, like a reading lamp just off screen
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        (if (dark) Color(0xFF6B4E33) else Color(0xFFFFF6E6)).copy(alpha = 0.55f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.18f, -size.height * 0.05f),
                    radius = size.width * 0.95f
                )
            )
            // corner vignette keeps the eye on the shelf
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0x66000000)),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.maxDimension * 0.85f
                )
            )
        }
        content()
    }
}

/**
 * A single curved shelf board seen slightly from above. The top edge bows down in the
 * middle exactly like a real board under load, which is what sells the curve.
 */
@Composable
fun ShelfBoard(
    dark: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp
) {
    val top = if (dark) Wood.boardDark else Wood.boardLight
    val mid = if (dark) Wood.boardDarkMid else Wood.boardMid
    val deep = if (dark) Wood.boardDarkDeep else Wood.boardDeep
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height

        val face = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(w / 2f, h * 0.30f, w, 0f)
            lineTo(w, h * 0.62f)
            quadraticBezierTo(w / 2f, h * 1.04f, 0f, h * 0.62f)
            close()
        }
        drawPath(face, Brush.verticalGradient(listOf(top, mid, deep)))

        // Lit top lip, then a shadow line underneath it: this is the "edge" of the wood.
        val lip = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(w / 2f, h * 0.30f, w, 0f)
        }
        drawPath(
            lip,
            Color(0xFFFFE9C8).copy(alpha = if (dark) 0.22f else 0.5f),
            style = Stroke(width = 1.6.dp.toPx())
        )
        drawPath(
            Path().apply {
                moveTo(0f, 2.5.dp.toPx())
                quadraticBezierTo(w / 2f, h * 0.30f + 2.5.dp.toPx(), w, 2.5.dp.toPx())
            },
            Color.Black.copy(alpha = 0.20f),
            style = Stroke(width = 1.dp.toPx())
        )

        // Long, uneven grain lines.
        var y = h * 0.20f
        var i = 0
        while (y < h * 0.92f) {
            val drift = h * 0.10f
            drawLine(
                color = Wood.grain,
                start = Offset(0f, y),
                end = Offset(w, y - drift),
                strokeWidth = 0.8.dp.toPx(),
                alpha = if (i % 3 == 0) 0.10f else 0.05f
            )
            y += 3.2.dp.toPx()
            i++
        }
    }
}

/**
 * A single book seen spine-on: cloth or leather boards, gilt bands, a lean, and a ribbon
 * for books in progress. Title runs down the spine the way a real one does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookSpine(
    spine: Spine,
    shelfHeight: Dp,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val widthDp = ShelfMath.spineWidthDp(spine.id, spine.pages)
    val spineHeight = shelfHeight * ShelfMath.spineHeightFraction(spine.id)
    val tint = Color(ShelfMath.spineTintArgb(spine.id))
    val lean = ShelfMath.leanDegrees(spine.id)
    val pale = tint.luminance() > 0.42f
    val labelColor = if (pale) Color(0xFF2B1E14) else Color(0xFFF2E3C6)
    val ribbonDp = ShelfMath.ribbonHeightDp(spine.id, spine.progress, spineHeight.value)

    Box(
        modifier
            .width(widthDp.dp)
            .height(spineHeight)
            .graphicsLayer {
                rotationZ = lean
                shadowElevation = 2.5.dp.toPx()
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .clip(RoundedCornerShape(topStart = 2.5.dp, topEnd = 2.5.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { onLongPress?.invoke() }
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val light = tint.copy(
                red = (tint.red + 0.20f).coerceAtMost(1f),
                green = (tint.green + 0.18f).coerceAtMost(1f),
                blue = (tint.blue + 0.16f).coerceAtMost(1f)
            )
            val shadow = Color(
                red = tint.red * 0.55f,
                green = tint.green * 0.55f,
                blue = tint.blue * 0.55f
            )
            // curved spine: light catches the middle, both edges fall away
            drawRect(
                Brush.horizontalGradient(
                    0f to shadow,
                    0.30f to light,
                    0.55f to tint,
                    1f to shadow
                )
            )
            // cloth grain
            var x = 2.dp.toPx()
            while (x < w) {
                drawLine(
                    Color.White,
                    Offset(x, 0f),
                    Offset(x, h),
                    strokeWidth = 0.6.dp.toPx(),
                    alpha = 0.035f
                )
                x += 3.dp.toPx()
            }
            // gilt bands top and bottom
            val band = 3.2.dp.toPx()
            drawRect(Wood.gold.copy(alpha = 0.55f), Offset(0f, 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(w, band))
            drawRect(Wood.gold.copy(alpha = 0.40f), Offset(0f, h - band - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(w, band))
            // head and tail shadow, plus the board edges of the binding
            drawRect(Color.Black.copy(alpha = 0.30f), Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, 1.5.dp.toPx()))
            drawRect(
                Color.Black.copy(alpha = 0.42f),
                Offset(0f, h - 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w, 2.dp.toPx())
            )
            drawRect(Color.White.copy(alpha = 0.12f), Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), h))
        }

        Text(
            text = spine.title.uppercase(),
            modifier = Modifier
                .align(Alignment.Center)
                .rotate(-90f)
                .width((spineHeight.value - 20f).coerceAtLeast(24f).dp)
                .padding(horizontal = 2.dp),
            color = labelColor,
            fontFamily = Playfair,
            fontSize = 8.5.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        // bookmark ribbon for anything part-read
        if (ribbonDp > 6f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 1.dp)
                    .width(3.dp)
                    .height(ribbonDp.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFFE8C36A), Color(0xFFC7A24A))))
            )
        }
    }
}

/**
 * One horizontal shelf of books. Books loop continuously: after the last spine the row
 * starts again, so a short library still feels like a full bookcase.
 */
@Composable
fun ShelfRow(
    spines: List<Spine>,
    dark: Boolean,
    shelfHeight: Dp,
    modifier: Modifier = Modifier,
    looping: Boolean = true,
    emptyLabel: String = "Nothing on this shelf yet",
    onOpen: (Spine) -> Unit,
    onLongPress: ((Spine) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(shelfHeight), contentAlignment = Alignment.BottomStart) {
            // contact shadow where the books meet the board
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.38f else 0.22f))
                        )
                    )
            )
            if (spines.isEmpty()) {
                Text(
                    emptyLabel,
                    Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 10.dp),
                    color = if (dark) Color(0xFFD8CBBA) else Color(0xFF6B5641),
                    fontSize = 12.sp
                )
            } else {
                val loop = looping && spines.size > 2
                val total = if (loop) spines.size * 60 else spines.size
                val state = rememberLazyListState(
                    initialFirstVisibleItemIndex = if (loop) spines.size * 30 else 0
                )
                LazyRow(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(shelfHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    items(count = total) { index ->
                        val spine = spines[ShelfMath.wrapIndex(index, spines.size)]
                        BookSpine(
                            spine = spine,
                            shelfHeight = shelfHeight,
                            dark = dark,
                            onOpen = { onOpen(spine) },
                            onLongPress = onLongPress?.let { press -> { press(spine) } }
                        )
                    }
                }
            }
        }
        ShelfBoard(dark, height = 22.dp)
    }
}

/** A titled shelf: heading, the row of spines, then the board. */
@Composable
fun TitledShelf(
    title: String,
    subtitle: String?,
    spines: List<Spine>,
    dark: Boolean,
    shelfHeight: Dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    emptyLabel: String = "Nothing on this shelf yet",
    onOpen: (Spine) -> Unit,
    onLongPress: ((Spine) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (dark) Color(0xFFF3E8D8) else Ink,
                    fontFamily = Playfair,
                    fontSize = 17.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = if (dark) Color(0xFFC9BCA9) else Color(0xFF6B5641),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            trailing?.invoke()
        }
        ShelfRow(
            spines = spines,
            dark = dark,
            shelfHeight = shelfHeight,
            emptyLabel = emptyLabel,
            onOpen = onOpen,
            onLongPress = onLongPress
        )
    }
}

/** Compact shelf used inside Studio, so generated books sit on their own little bookcase. */
@Composable
fun MiniShelf(
    spines: List<Spine>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Your Studio shelf",
    onOpen: (Spine) -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp),
            color = if (dark) Color(0xFFF3E8D8) else Ink,
            fontFamily = Playfair,
            fontSize = 15.sp
        )
        ShelfRow(
            spines = spines,
            dark = dark,
            shelfHeight = 86.dp,
            emptyLabel = "Books you make will stand here",
            onOpen = onOpen
        )
    }
}

/** Small helper for a shelf-sized loading state that still looks like furniture. */
@Composable
fun EmptyShelf(dark: Boolean, modifier: Modifier = Modifier, label: String = "") {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.BottomStart) {
            if (label.isNotBlank()) {
                Text(
                    label,
                    Modifier.padding(start = 20.dp, bottom = 10.dp),
                    color = if (dark) Color(0xFFC9BCA9) else Color(0xFF6B5641),
                    fontSize = 12.sp
                )
            }
        }
        ShelfBoard(dark, height = 22.dp)
    }
}
