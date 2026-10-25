package com.lantern.library.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lantern.library.ui.theme.Brass
import com.lantern.library.ui.theme.BrassHi
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair

/**
 * A revolving bookcase: a row of spines you sweep sideways, where each spine turns and drops
 * as it leaves the middle so the shelf reads as a curve you are standing inside.
 *
 * Everything is ordinary Compose layout plus [graphicsLayer] — a snapping [LazyRow] for the
 * motion and per-item rotation/translation for the illusion. There is no GL surface, no
 * render thread and no texture: the items are real composables, so type stays crisp and the
 * shelf composes with the rest of the app (blur, fades, overlays) for free.
 *
 * The pose maths lives in [RevolvingMath] and is unit tested.
 *
 * A note on why the tilt is calculated in [graphicsLayer] rather than in composition: the
 * layer block runs at draw time, so reading the scroll position there turns the spines without
 * recomposing them. That is what keeps a sweep at frame rate instead of dropping.
 */
@Composable
fun RevolvingBookshelf(
    spines: List<Spine>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spineWidth: Dp = 78.dp,
    spineHeight: Dp = 236.dp,
    spacing: Dp = 12.dp,
    maxRotation: Float = RevolvingMath.MAX_ROTATION,
    arcDrop: Dp = 34.dp,
    liftedId: String? = null,
    onTap: (Spine) -> Unit = {},
    onLongPress: (Spine) -> Unit = {},
    onCentred: (Spine) -> Unit = {}
) {
    // Snap as it is swiped, so a book always settles centred under the eye rather than
    // stopping half-turned with its title at an angle.
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val density = LocalDensity.current
    val dropPx = with(density) { arcDrop.toPx() }
    val liftPx = with(density) { 18.dp.toPx() }

    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val spinePx = with(density) { spineWidth.toPx() }
        // Half a screen of padding at each end, so the first and last book can reach the
        // middle of the shelf. Without it they could only ever sit at the edges.
        val edgePadding = ((widthPx - spinePx) / 2f).coerceAtLeast(0f)

        // Which book the shelf is currently offering: the one nearest the centre.
        val centredId by remember(spines) {
            derivedStateOf {
                val info = state.layoutInfo
                val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                var bestId: String? = null
                var best = Float.MAX_VALUE
                for (item in info.visibleItemsInfo) {
                    val d = kotlin.math.abs((item.offset + item.size / 2f) - centre)
                    if (d < best) {
                        best = d
                        bestId = spines.getOrNull(item.index)?.id
                    }
                }
                bestId
            }
        }

        // Tell the host which book the shelf is offering, so it can caption it.
        androidx.compose.runtime.LaunchedEffect(centredId) {
            spines.firstOrNull { it.id == centredId }?.let(onCentred)
        }

        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = with(density) { edgePadding.toDp() }),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(spines, key = { _, spine -> spine.id }) { index, spine ->
                val lifted = spine.id == liftedId
                val offering = spine.id == centredId
                RevolvingSpine(
                    spine = spine,
                    index = index,
                    state = state,
                    spineWidth = spineWidth,
                    spineHeight = spineHeight,
                    maxRotation = maxRotation,
                    dropPx = dropPx,
                    liftPx = if (lifted) liftPx else 0f,
                    lifted = lifted,
                    offering = offering,
                    onTap = { onTap(spine) },
                    onLongPress = { onLongPress(spine) }
                )
            }
        }
    }
}

/**
 * One spine. Its pose is worked out inside [graphicsLayer] from the list's current layout, so
 * sweeping the shelf costs no recomposition at all.
 */
@Composable
private fun RevolvingSpine(
    spine: Spine,
    index: Int,
    state: LazyListState,
    spineWidth: Dp,
    spineHeight: Dp,
    maxRotation: Float,
    dropPx: Float,
    liftPx: Float,
    lifted: Boolean,
    offering: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val leather = remember(spine.id) { leatherFor(spine.title, spine.id) }

    Box(
        modifier = Modifier
            .width(spineWidth)
            .height(spineHeight)
            // Drawn in the order of the row, but the offered book floats above its neighbours.
            .zIndex(if (lifted || offering) 1f else 0f)
            .graphicsLayer {
                val info = state.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                if (item != null) {
                    val viewportWidth =
                        (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    val itemCentre = item.offset + item.size / 2f
                    val fraction = RevolvingMath.offsetFraction(
                        itemCentre, viewportCentre, viewportWidth
                    )
                    // The revolving door: each spine turns about its own vertical edge.
                    rotationY = RevolvingMath.rotationY(fraction, maxRotation)
                    // The arch: edges fall away below the middle, so the row curves down.
                    translationY = RevolvingMath.arcDrop(fraction, dropPx) - liftPx
                    scaleX = RevolvingMath.scale(fraction)
                    scaleY = RevolvingMath.scale(fraction)
                    alpha = RevolvingMath.alpha(fraction)
                    // A long lens keeps the turned spines readable instead of fish-eyed.
                    cameraDistance = RevolvingMath.cameraDistance(density)
                    // Turn about the spine's own axis, not a shared pivot, or the row shears.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                }
            }
            .pointerInput(spine.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        SpineFace(
            spine = spine,
            leather = leather,
            lifted = lifted,
            offering = offering,
            height = spineHeight
        )
    }
}

/** The painted spine: leather, gilt bands and the title reading down it. */
@Composable
private fun SpineFace(
    spine: Spine,
    leather: Leather,
    lifted: Boolean,
    offering: Boolean,
    height: Dp
) {
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // A lifted book leans out of the shelf a little further than the tilt already
                // gives it, which is what makes the tap feel like pulling it out.
                if (lifted) rotationZ = -2.5f
            }
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(leather.deep, leather.base, leather.deep)
                )
            )
            .border(
                width = if (offering) 1.5.dp else 0.75.dp,
                color = if (offering) BrassHi else Brass.copy(alpha = 0.45f),
                shape = shape
            )
    ) {
        // head and tail bands, the way a bound book is finished
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .align(Alignment.TopCenter)
                .background(leather.deep.copy(alpha = 0.85f))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .align(Alignment.BottomCenter)
                .background(leather.deep.copy(alpha = 0.85f))
        )
        // gilt rules just inside the bands
        Box(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brass.copy(alpha = 0.55f))
                .align(Alignment.TopCenter)
        )
        Box(
            Modifier
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brass.copy(alpha = 0.55f))
                .align(Alignment.BottomCenter)
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The title is rotated a quarter turn so it reads down the spine, which is how a
            // real book is titled. Laid out at (spine height) wide first, so the rotated text
            // has room to run the length of the spine instead of being ellipsised to nothing.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = spine.title.uppercase(),
                    color = if (leather.lightInk) Color(0xFF2B1D12) else Parchment,
                    fontFamily = Playfair,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(height * 0.66f)
                        .graphicsLayer { rotationZ = 90f }
                )
            }
            if (spine.progress > 0.01f) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(BrassHi.copy(alpha = 0.9f))
                )
            }
        }
    }
}

/** Colours for a spine, derived from its title so a book always looks the same. */
internal data class Leather(val base: Color, val deep: Color, val lightInk: Boolean)

private val cloth = listOf(
    0xFF8C3B32 to 0xFF5E241E, // oxblood
    0xFF2F4A45 to 0xFF1D302C, // bottle green
    0xFF33465E to 0xFF202D3F, // midnight blue
    0xFF7A5726 to 0xFF523913, // antique gold
    0xFF5B3A56 to 0xFF3B2237, // plum
    0xFF3E4F3A to 0xFF273326, // fern
    0xFF8A6234 to 0xFF5C3F1F, // tan leather
    0xFF53333A to 0xFF352025, // maroon
    0xFF2E5057 to 0xFF1C3439, // teal
    0xFF6B4A2F to 0xFF452D1B  // walnut
)

/**
 * A stable colour for a book. Hashed from the title and id so the same book is always the same
 * colour, and two books rarely collide in a row.
 */
internal fun leatherFor(title: String, id: String): Leather {
    val hash = title.hashCode() * 31 + id.hashCode()
    val index = ((hash % cloth.size) + cloth.size) % cloth.size
    val (base, deep) = cloth[index]
    // the two lightest cloths need dark type to stay legible
    val light = index == 3 || index == 6
    return Leather(Color(base), Color(deep), light)
}
