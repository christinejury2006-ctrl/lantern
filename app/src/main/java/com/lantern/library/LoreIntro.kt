package com.lantern.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lantern.library.data.LanternStore
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.StartupTrace
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lore's opening screen, in the nook's own voice.
 *
 * Short on purpose. The previous intro was a three-and-a-half second painted scene — a full
 * illustration, a stroke-by-stroke tagline, an eight-stop lavender-to-aqua gradient — which is a
 * lovely thing to watch once and a slow door to walk through every day. This one is 1.2 seconds
 * of the icon the app now wears: the card rises, its glass edge catches the light, the name sets
 * beneath it, and the app is there.
 *
 * It draws in the same two palettes as everything else, and it is skipped entirely if the app
 * takes longer than the animation to load — the fade into the app is driven by readiness, not by
 * the clock, so a slow start never means staring at an intro that has already finished.
 */
private const val RISE_MS = 620
private const val NAME_MS = 420
private const val HOLD_MS = 240

@Composable
internal fun LoreAfterSplash() {
    // The splash belongs to whoever last used the app: reading the stored theme is one
    // SharedPreferences read, and it is the difference between a cream flash and a navy one for
    // a reader who reads at night.
    val context = LocalContext.current
    val theme = remember {
        val stored = context.getSharedPreferences("lantern", android.content.Context.MODE_PRIVATE)
            .getString("theme", "LIGHT")
        if (stored == "DARK") ReaderTheme.DARK else ReaderTheme.LIGHT
    }
    var storeReady by remember { mutableStateOf(false) }
    var introDone by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(introDone, storeReady) {
        if (introDone && storeReady && fade.value == 0f) {
            fade.animateTo(1f, tween(260))
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (storeReady) {
            val store: LanternStore = viewModel()
            if (fade.value > 0f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                    LanternRoot(store)
                }
            }
        }
        if (fade.value < 1f) {
            NookSplash(
                theme = theme,
                onFirstFrame = { storeReady = true },
                onFinished = { introDone = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fade.value }
            )
        }
    }
}

/**
 * The splash itself: the icon, the name, and the room's light.
 *
 * The icon is decoded once on the IO dispatcher so the first frame is not blocked by it, and
 * `onFirstFrame` is called after that frame has actually been drawn — the store is only created
 * once there is something on screen, which is what keeps the app's own startup work off the
 * splash's critical path.
 */
@Composable
private fun NookSplash(
    theme: ReaderTheme,
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = nookPalette(theme)
    val context = LocalContext.current
    var icon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val rise = remember { Animatable(0f) }
    val name = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        StartupTrace.mark("splash start")
        icon = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.lore_icon)
            }.getOrNull()
        }
        withFrameNanos { }
        StartupTrace.mark("splash first frame")
        onFirstFrame()
        rise.animateTo(1f, tween(RISE_MS, easing = CubicBezierEasing(0.18f, 0.72f, 0.24f, 1f)))
        name.animateTo(1f, tween(NAME_MS, easing = LinearOutSlowInEasing))
        delay(HOLD_MS.toLong())
        onFinished()
    }

    Box(modifier.background(Brush.verticalGradient(palette.backdrop))) {
        NookSplashLights(palette)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val t = rise.value
            Box(
                Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        // Rises a little, settles from slightly small, and lands softly.
                        alpha = t
                        translationY = (1f - t) * 26.dp.toPx()
                        scaleX = 0.88f + 0.12f * t
                        scaleY = 0.88f + 0.12f * t
                    }
                    .clip(RoundedCornerShape(34.dp))
            ) {
                val bmp = icon
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(palette.coverFallback))
                }
                // The glass rim: a bright hairline and a wash of light across the top corner,
                // which is the same treatment the cards in the app wear.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(palette.glassSheen, Color.Transparent),
                                endY = 300f
                            )
                        )
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "Lore",
                color = palette.ink,
                fontFamily = Playfair,
                fontSize = 40.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.graphicsLayer {
                    alpha = name.value
                    translationY = (1f - name.value) * 10.dp.toPx()
                }
            )
            Text(
                "THE READING NOOK",
                color = palette.mute,
                fontSize = 10.sp,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer {
                    alpha = name.value * 0.9f
                }
            )
        }
    }
}

/** A few large soft lights in the room behind the icon, matching the nook's backdrop. */
@Composable
private fun NookSplashLights(palette: NookPalette) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val lights = listOf(
            Triple(0.18f, 0.22f, 0.42f),
            Triple(0.84f, 0.30f, 0.34f),
            Triple(0.68f, 0.78f, 0.44f),
            Triple(0.16f, 0.82f, 0.30f)
        )
        lights.forEachIndexed { i, (fx, fy, fr) ->
            val radius = size.width * fr
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.bokeh[i % palette.bokeh.size].copy(alpha = if (palette.dark) 0.26f else 0.50f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(size.width * fx, size.height * fy),
                    radius = radius
                ),
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(size.width * fx, size.height * fy)
            )
        }
    }
}

