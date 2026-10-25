package com.lantern.library.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CoverCache
import com.lantern.library.data.LibraryBook
import com.lantern.library.ui.theme.Brass
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

/**
 * The moment a book leaves the shelf.
 *
 * The room behind softens, and the book rises: a real closed hardback whose boards, spine
 * and page block are built out of the cover's own colours, so the jacket reads as printed
 * onto the binding rather than pasted on top of a rectangle. Progress sits underneath.
 */
@Composable
fun BookLiftOverlay(
    book: LibraryBook,
    dark: Boolean,
    onRead: () -> Unit,
    onDismiss: () -> Unit
) {
    var entered by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) { entered = true }
    val lift by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "book-lift"
    )

    val context = LocalContext.current
    var cover by remember(book.id, book.remoteCover, book.localCover) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(book.id, book.remoteCover, book.localCover) {
        val res = book.localCover
        cover = if (res != null) {
            runCatching { BitmapFactory.decodeResource(context.resources, res) }.getOrNull()
        } else {
            CoverCache.load(book.remoteCover)
        }
    }

    val scrim = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99110B06))
            .clickable(indication = null, interactionSource = scrim, onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 34.dp)
                .graphicsLayer {
                    alpha = lift
                    translationY = (1f - lift) * 70.dp.toPx()
                    scaleX = 0.9f + 0.1f * lift
                    scaleY = 0.9f + 0.1f * lift
                    rotationX = (1f - lift) * 12f
                    cameraDistance = 18f * density
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .clickable(onClick = onRead),
                contentAlignment = Alignment.Center
            ) {
                BoundBook(cover, dark)
            }

            Spacer(Modifier.height(16.dp))

            Column(Modifier.fillMaxWidth(0.84f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF3A2A1C))
                ) {
                    val p = book.progress.coerceIn(0f, 1f)
                    if (p > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(p)
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Brass, Color(0xFFE8C87A))))
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(progressLabel(book), color = Color(0xFFD9C7A8), fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                book.title,
                color = NightText,
                fontFamily = Playfair,
                fontSize = 21.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                book.author,
                color = Color(0xFFC6B49A),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (book.progress > 0.005f) "Continue reading" else "Start reading",
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brass)
                    .clickable(onClick = onRead)
                    .padding(horizontal = 26.dp, vertical = 11.dp),
                color = Color(0xFF2A1B10),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text("Tap anywhere to put it back", color = Color(0xFF9C8A72), fontSize = 10.sp)
        }
    }
}

/**
 * A closed hardback built from a cover: the boards take the cover's own colours, the page
 * block sits inside them, the spine is a rounded leather edge with raised bands, and the
 * jacket is laid flush with a little wear, so nothing looks stuck on.
 */
@Composable
private fun BoundBook(cover: Bitmap?, dark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val boardWidth = size.width * 0.66f
        val boardHeight = boardWidth / 0.685f
        val left = (size.width - boardWidth) / 2f - 6f
        val top = (size.height - boardHeight) / 2f
        val right = left + boardWidth
        val bottom = top + boardHeight
        val spineWidth = boardWidth * 0.075f

        val tint = coverTint(cover, dark)
        val light = android.graphics.Color.argb(
            255,
            (android.graphics.Color.red(tint) + 26).coerceAtMost(255),
            (android.graphics.Color.green(tint) + 20).coerceAtMost(255),
            (android.graphics.Color.blue(tint) + 16).coerceAtMost(255)
        )
        val deep = android.graphics.Color.argb(
            255,
            (android.graphics.Color.red(tint) * 0.55f).toInt(),
            (android.graphics.Color.green(tint) * 0.55f).toInt(),
            (android.graphics.Color.blue(tint) * 0.55f).toInt()
        )

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val shape = RectF(left, top, right, bottom)
            val radius = boardHeight * 0.012f

            // shadow the book casts on the room
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(150, 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(24f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            native.drawRoundRect(
                RectF(left + 4f, top + 16f, right + 8f, bottom + 22f),
                radius, radius, shadowPaint
            )

            // page block, peeking out under the boards
            val pages = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    right - 2f, top, right + 12f, top,
                    intArrayOf(
                        android.graphics.Color.argb(255, 232, 218, 190),
                        android.graphics.Color.argb(255, 203, 186, 155),
                        android.graphics.Color.argb(255, 168, 150, 122)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            native.drawRoundRect(
                RectF(left + 4f, top + 3f, right + 9f, bottom - 1f),
                radius, radius, pages
            )

            // boards, in the book's own colour
            val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    left, top, right, bottom,
                    intArrayOf(light, tint, deep),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            native.drawRoundRect(shape, radius, radius, boardPaint)

            // the jacket, flush with the boards
            if (cover != null && !cover.isRecycled) {
                val dst = RectF(left + 1f, top + 1f, right - 1f, bottom - 1f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
                native.save()
                native.clipRect(dst)
                native.drawBitmap(
                    cover,
                    android.graphics.Rect(0, 0, cover.width, cover.height),
                    dst,
                    paint
                )
                native.restore()

                // paper wear, and a soft light across the face so it reads as printed
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        left, top, right, bottom,
                        intArrayOf(
                            android.graphics.Color.argb(40, 255, 255, 255),
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.argb(70, 0, 0, 0)
                        ),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                native.drawRoundRect(dst, radius, radius, sheen)
            }

            // spine: wrapped from the same jacket, darkened into the round of the binding
            val spineRect = RectF(left, top, left + spineWidth, bottom)
            val spinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    left, top, left + spineWidth, top,
                    intArrayOf(deep, light, deep),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            native.drawRoundRect(spineRect, radius, radius, spinePaint)
            if (cover != null && !cover.isRecycled) {
                // carry the cover round the spine instead of stopping dead at the edge
                val strip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    alpha = 150
                }
                native.save()
                native.clipRect(spineRect)
                native.drawBitmap(
                    cover,
                    android.graphics.Rect(0, 0, (cover.width * 0.10f).toInt().coerceAtLeast(2), cover.height),
                    spineRect,
                    strip
                )
                native.restore()
            }
            val bands = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(120, 200, 169, 107) }
            listOf(0.09f, 0.91f).forEach { f ->
                native.drawRect(
                    left + spineWidth * 0.12f,
                    top + boardHeight * f,
                    left + spineWidth * 0.88f,
                    top + boardHeight * f + 2.5f,
                    bands
                )
            }

            // highlight along the hinge where the spine meets the board
            val hinge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    left + spineWidth, top, left + spineWidth * 2.6f, top,
                    intArrayOf(
                        android.graphics.Color.argb(110, 0, 0, 0),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            native.drawRect(
                left + spineWidth, top, left + spineWidth * 2.6f, bottom, hinge
            )

            // a warm falloff at the corners, so the book is not a clean vector rectangle
            val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    left + boardWidth / 2f, top + boardHeight / 2f, boardWidth * 0.95f,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.argb(120, 0, 0, 0)
                    ),
                    floatArrayOf(0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            native.drawRoundRect(shape, radius, radius, vignette)
        }
    }
}

/** Average colour of the cover's border, used for boards and spine. */
private fun coverTint(cover: Bitmap?, dark: Boolean): Int {
    val fallback = if (dark) android.graphics.Color.rgb(74, 52, 38) else android.graphics.Color.rgb(107, 74, 50)
    if (cover == null || cover.isRecycled) return fallback
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val stepX = (cover.width / 12).coerceAtLeast(1)
    val stepY = (cover.height / 16).coerceAtLeast(1)
    var y = 0
    while (y < cover.height) {
        var x = 0
        while (x < cover.width) {
            val px = cover.getPixel(x, y)
            val a = android.graphics.Color.alpha(px)
            if (a > 128) {
                r += android.graphics.Color.red(px)
                g += android.graphics.Color.green(px)
                b += android.graphics.Color.blue(px)
                n++
            }
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return fallback
    val rr = (r / n).toInt().coerceIn(12, 243)
    val gg = (g / n).toInt().coerceIn(12, 243)
    val bb = (b / n).toInt().coerceIn(12, 243)
    // deepen slightly: a printed board is darker than the artwork's average
    return android.graphics.Color.rgb((rr * 0.86f).toInt(), (gg * 0.86f).toInt(), (bb * 0.86f).toInt())
}

private fun progressLabel(book: LibraryBook): String {
    val pct = (book.progress * 100f).toInt().coerceIn(0, 100)
    if (book.pageCount <= 1) return "$pct% read"
    val page = (book.currentPage + 1).coerceIn(1, book.pageCount)
    return "$pct%  ·  page $page of ${book.pageCount}"
}
