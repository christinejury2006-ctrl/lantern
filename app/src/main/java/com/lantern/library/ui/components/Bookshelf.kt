package com.lantern.library.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CoverCache
import com.lantern.library.data.ShelfMath
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Playfair
import kotlin.math.abs

/**
 * One book as it stands on the shelf. It carries the real cover so the spine is drawn from
 * the book's own artwork — that is what makes a shelf readable at a glance instead of a row
 * of anonymous coloured blocks.
 */
data class Spine(
    val id: String,
    val title: String,
    val author: String,
    val pages: Int = 240,
    val progress: Float = 0f,
    val coverRes: Int? = null,
    val coverUrl: String? = null,
    val coverFallbacks: List<String> = emptyList()
)

/** Warm wood and plaster. These are the colours that carry the whole "cozy library" read. */
private object Wood {
    val wallTop = Color(0xFFF1E3D0)
    val wallBottom = Color(0xFFD9C3A5)
    val wallTopDark = Color(0xFF22180F)
    val wallBottomDark = Color(0xFF120C07)

    val cavityLight = Color(0xFF2C1C11)
    val cavityDark = Color(0xFF140C06)

    val boardFace = Color(0xFF6E4527)
    val boardFaceDeep = Color(0xFF3E2413)
    val boardTop = Color(0xFF9C6C42)
    val boardTopHi = Color(0xFFC08F5E)
    val boardLip = Color(0xFFD8AE7C)
    val boardDark = Color(0xFF4A2E1B)
    val boardDarkDeep = Color(0xFF22140B)
    val boardDarkTop = Color(0xFF6B4527)

    val gold = Color(0xFFC8A96B)
    val pagesTop = Color(0xFFE9DCC0)
    val pagesTopDeep = Color(0xFFCDBB99)
}

/**
 * The room: plaster wall, lamp glow, vignette. Quiet on purpose — the books hold the colour.
 */
@Composable
fun ShelfRoom(
    dark: Boolean,
    modifier: Modifier = Modifier,
    wallAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val a = if (wallAlpha.isFinite()) wallAlpha.coerceIn(0f, 1f) else 1f
    val top = (if (dark) Wood.wallTopDark else Wood.wallTop).copy(alpha = a)
    val bottom = (if (dark) Wood.wallBottomDark else Wood.wallBottom).copy(alpha = a)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        (if (dark) Color(0xFF7A5836) else Color(0xFFFFF8EA)).copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.15f, -size.height * 0.08f),
                    radius = size.width
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0x59000000)),
                    center = Offset(size.width * 0.5f, size.height * 0.40f),
                    radius = size.maxDimension * 0.8f
                )
            )
        }
        content()
    }
}

/**
 * The wooden case behind the books: a deep recess so the books sit *inside* a shelf rather
 * than floating on a line, with a shadowed back panel and a lit upper lip.
 */
@Composable
private fun ShelfCavity(dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val light = if (dark) Wood.cavityLight else Color(0xFF3B2718)
        val darkC = if (dark) Wood.cavityDark else Color(0xFF1E120A)
        drawRect(
            Brush.verticalGradient(
                0f to darkC,
                0.35f to light,
                1f to Color(0xFF120B06)
            )
        )
        // wood grain on the back panel
        var y = size.height * 0.1f
        while (y < size.height) {
            drawLine(
                Color.Black,
                Offset(0f, y),
                Offset(size.width, y - 3f),
                strokeWidth = 1.2f,
                alpha = 0.06f
            )
            y += 9f
        }
    }
}

/**
 * The shelf board, in three faces: the top surface you look down onto, the lit front lip,
 * and the deep front face. The front edge arcs upward in the middle the way a long board
 * reads in perspective, and the books are laid on the same arc.
 */
@Composable
private fun ShelfBoard3D(
    dark: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val rise = h * 0.34f

        // front face
        val face = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, rise)
            quadraticBezierTo(w / 2f, 0f, w, rise)
            lineTo(w, h - 4f)
            quadraticBezierTo(w / 2f, h - rise * 0.55f, 0f, h - 4f)
            close()
        }
        drawPath(
            face,
            Brush.verticalGradient(
                listOf(
                    if (dark) Wood.boardDark else Wood.boardFace,
                    if (dark) Wood.boardDarkDeep else Wood.boardFaceDeep
                )
            )
        )
        // the top surface the books rest on
        val top = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, rise)
            quadraticBezierTo(w / 2f, 0f, w, rise)
            lineTo(w, rise + h * 0.16f)
            quadraticBezierTo(w / 2f, h * 0.16f, 0f, rise + h * 0.16f)
            close()
        }
        drawPath(
            top,
            Brush.verticalGradient(
                listOf(
                    if (dark) Wood.boardDarkTop else Wood.boardTopHi,
                    if (dark) Wood.boardDark else Wood.boardTop
                )
            )
        )
        // bright lip where the top surface turns into the front face
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, rise)
                quadraticBezierTo(w / 2f, 0f, w, rise)
            },
            if (dark) Wood.boardLip.copy(alpha = 0.55f) else Wood.boardLip,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
        // grain following the arc
        var y = h * 0.42f
        while (y < h) {
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, y + rise * 0.2f)
                    quadraticBezierTo(w / 2f, y - rise * 0.8f, w, y + rise * 0.2f)
                },
                Color.Black.copy(alpha = 0.07f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f)
            )
            y += 6f
        }
    }
}

/** Where a book sits in the arc, worked out from the live scroll offset. */
private data class Placement(val liftPx: Float, val dim: Float)

/**
 * A book standing on the shelf, seen from the front: cover art wrapped around the spine,
 * a cream page block along the top edge, the binding shaded as a cylinder, and the title
 * running down it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookSpine3D(
    spine: Spine,
    shelfHeight: Dp,
    dark: Boolean,
    place: () -> Placement,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val widthDp = ShelfMath.spineWidthDp(spine.id, spine.pages)
    val bodyHeight = shelfHeight * ShelfMath.spineHeightFraction(spine.id)
    val lean = ShelfMath.leanDegrees(spine.id)
    val tint = Color(ShelfMath.spineTintArgb(spine.id))
    val pale = tint.luminance() > 0.45f
    val labelColor = if (pale) Color(0xFF20150E) else Color(0xFFF6EBD6)
    val ribbon = ShelfMath.ribbonHeightDp(spine.id, spine.progress, bodyHeight.value)

    Box(
        modifier
            .width(widthDp.dp)
            .height(bodyHeight)
            .graphicsLayer {
                // Read while drawing, not while composing: the arc follows the scroll
                // without recomposing every spine on every frame.
                val spot = place()
                translationY = spot.liftPx
                rotationZ = lean
                // books toward the edges of the screen turn slightly away from us
                scaleY = 1f - spot.dim * 0.05f
                alpha = 1f - spot.dim * 0.10f
                shadowElevation = (2f + 3f * (1f - spot.dim)).dp.toPx()
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .combinedClickable(onClick = onOpen, onLongClick = { onLongPress?.invoke() })
    ) {
        Column(Modifier.fillMaxSize()) {
            // cream page block visible along the top of the book
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Wood.pagesTopDeep, Wood.pagesTop, Wood.pagesTopDeep)
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(bottomStart = 1.5.dp, bottomEnd = 1.5.dp))
                    .background(tint)
            ) {
                SpineArt(spine, tint)

                // Cylindrical binding: both edges fall into shadow, the middle catches light.
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.14f to Color.Black.copy(alpha = 0.18f),
                            0.42f to Color.White.copy(alpha = 0.16f),
                            0.62f to Color.Transparent,
                            0.88f to Color.Black.copy(alpha = 0.26f),
                            1f to Color.Black.copy(alpha = 0.60f)
                        )
                    )
                    // gilt rules at head and tail
                    drawRect(
                        Wood.gold.copy(alpha = 0.50f),
                        Offset(0f, 5.dp.toPx()),
                        Size(size.width, 1.6.dp.toPx())
                    )
                    drawRect(
                        Wood.gold.copy(alpha = 0.38f),
                        Offset(0f, size.height - 6.dp.toPx()),
                        Size(size.width, 1.6.dp.toPx())
                    )
                }

                Text(
                    text = spine.title.uppercase(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer { rotationZ = -90f }
                        .width((bodyHeight.value - 16f).coerceAtLeast(20f).dp)
                        .padding(horizontal = 1.dp),
                    color = labelColor,
                    fontFamily = Playfair,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(
                            color = if (pale) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.75f),
                            offset = Offset(0f, 1f),
                            blurRadius = 3f
                        )
                    )
                )

                // bookmark ribbon for anything part-read
                if (ribbon > 6f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 4.dp)
                            .width(3.dp)
                            .height(ribbon.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFFE9C67A), Color(0xFFB8903F))
                                )
                            )
                    )
                }
            }
        }
    }
}

/** Cover art stretched around the spine; falls back to bookbinding cloth. */
@Composable
private fun SpineArt(spine: Spine, tint: Color) {
    val res = spine.coverRes
    val hasUrl = !spine.coverUrl.isNullOrBlank() || spine.coverFallbacks.isNotEmpty()
    when {
        res != null -> Image(
            painterResource(res),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        hasUrl -> {
            var bmp by remember(spine.coverUrl) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(spine.coverUrl) {
                bmp = CoverCache.load(spine.coverUrl, spine.coverFallbacks)
            }
            val image = bmp
            if (image != null && !image.isRecycled) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                ClothFill(tint)
            }
        }
        else -> ClothFill(tint)
    }
}

@Composable
private fun ClothFill(tint: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(tint.copy(alpha = 0.92f), tint, tint.copy(alpha = 0.85f))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            var x = 2.dp.toPx()
            while (x < size.width) {
                drawLine(
                    Color.White,
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = 0.6.dp.toPx(),
                    alpha = 0.04f
                )
                x += 2.5.dp.toPx()
            }
        }
    }
}

/**
 * One shelf of books, sitting inside a wooden case.
 *
 * The row is drawn straight but laid on an arc: books near the middle of the screen stand
 * slightly taller and closer, books toward the edges sit lower and turn away — the same
 * cue a photograph of a bowed shelf gives. Rows loop so a short library still fills a case.
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
    val density = LocalDensity.current
    val gap = 2.dp
    val sidePad = 18.dp

    // Every spine's width is deterministic, so the row's arc can be worked out analytically
    // from the scroll position instead of measuring each child.
    val widthsPx = remember(spines) {
        spines.map { with(density) { ShelfMath.spineWidthDp(it.id, it.pages).dp.toPx() } }
    }
    val gapPx = with(density) { gap.toPx() }
    val padPx = with(density) { sidePad.toPx() }
    val prefix = remember(widthsPx, gapPx) {
        IntArray(widthsPx.size + 1).also { acc ->
            for (i in widthsPx.indices) acc[i + 1] = (acc[i] + widthsPx[i] + gapPx).toInt()
        }
    }
    val oneCopy = prefix.lastOrNull() ?: 0
    val loop = looping && spines.size > 2
    val copies = if (loop) 3 else 1
    val scroll = rememberScrollState(
        initial = if (loop) (padPx + oneCopy).toInt() else 0
    )

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(shelfHeight)) {
            val screenPx = with(density) { maxWidth.toPx() }
            val half = screenPx / 2f
            val arc = with(density) { 13.dp.toPx() }

            ShelfCavity(dark, Modifier.fillMaxSize())

            Row(Modifier.fillMaxSize().horizontalScroll(scroll), verticalAlignment = Alignment.Bottom) {
                if (spines.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(start = sidePad, bottom = 10.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Text(
                            emptyLabel,
                            color = if (dark) Color(0xFFD6C5AE) else Color(0xFF5E4A36),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    for (copy in 0 until copies) {
                        for (i in spines.indices) {
                            val spine = spines[i]
                            // x of this book within the whole row, before scrolling
                            val rowX = padPx + copy * oneCopy + prefix[i]
                            val width = widthsPx[i]
                            BookSpine3D(
                                spine = spine,
                                shelfHeight = shelfHeight,
                                dark = dark,
                                place = {
                                    val x = rowX - scroll.value
                                    // 0 in the middle of the screen, 1 at either edge
                                    val t = ((x + width / 2f - half) / half)
                                        .coerceIn(-1.35f, 1.35f)
                                    val closeness = (1f - t * t).coerceIn(0f, 1f)
                                    Placement(
                                        liftPx = -arc * closeness,
                                        dim = (abs(t) * 0.9f).coerceIn(0f, 1f)
                                    )
                                },
                                modifier = Modifier.padding(end = gap),
                                onOpen = { onOpen(spine) },
                                onLongPress = onLongPress?.let { press -> { press(spine) } }
                            )
                        }
                    }
                }
            }

            // books sit into the board: this shadow is what makes them look placed, not pasted
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.45f else 0.30f))
                        )
                    )
            )
        }
        ShelfBoard3D(dark, height = 30.dp)
    }

    // keep the loop seamless by hopping a copy when the row drifts too far
    if (loop && oneCopy > 0) {
        LaunchedEffect(scroll, oneCopy) {
            snapshotFlow { scroll.value }.collect { v ->
                when {
                    v < oneCopy * 0.45f -> scroll.scrollTo(v + oneCopy)
                    v > oneCopy * 1.55f -> scroll.scrollTo(v - oneCopy)
                }
            }
        }
    }
}

/** A titled shelf: heading, the row, then the board. */
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
        if (title.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = if (dark) Color(0xFFF4EADB) else Ink,
                        fontFamily = Playfair,
                        fontSize = 17.sp
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = if (dark) Color(0xFFC9B79F) else Color(0xFF6B5641),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
                trailing?.invoke()
            }
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

/** Compact case for Studio, so the books it generates live on a shelf of their own. */
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
            color = if (dark) Color(0xFFF4EADB) else Ink,
            fontFamily = Playfair,
            fontSize = 15.sp
        )
        ShelfRow(
            spines = spines,
            dark = dark,
            shelfHeight = 92.dp,
            emptyLabel = "Books you make will stand here",
            onOpen = onOpen
        )
    }
}

/** Loading / empty state that still looks like furniture. */
@Composable
fun EmptyShelf(dark: Boolean, modifier: Modifier = Modifier, label: String = "") {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.BottomStart) {
            ShelfCavity(dark, Modifier.fillMaxSize())
            if (label.isNotBlank()) {
                Text(
                    label,
                    Modifier.padding(start = 20.dp, bottom = 10.dp),
                    color = if (dark) Color(0xFFC9B79F) else Color(0xFF5E4A36),
                    fontSize = 12.sp
                )
            }
        }
        ShelfBoard3D(dark, height = 30.dp)
    }
}
