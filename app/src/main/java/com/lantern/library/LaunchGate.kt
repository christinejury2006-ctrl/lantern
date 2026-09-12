package com.lantern.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lantern.library.data.LanternStore
import kotlinx.coroutines.launch

private val LoreOmbre = Brush.verticalGradient(
    listOf(
        Color(0xFFC8A1F3),
        Color(0xFFC9B8F8),
        Color(0xFF7EC8E8),
        Color(0xFF6ED4C8)
    )
)

@Composable
fun LaunchGate() {
    var firstFrame by remember { mutableStateOf(false) }
    var introDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        com.lantern.library.data.StartupTrace.mark("first frame drawn")
        firstFrame = true
    }
    Box(Modifier.fillMaxSize().background(LoreOmbre)) {
        LoreIntro(onFinished = { introDone = true })
        if (firstFrame) {
            val store: LanternStore = viewModel()
            if (introDone && store.hydrated) {
                LanternRoot(store)
            }
        }
    }
}

@Composable
private fun LoreIntro(onFinished: () -> Unit) {
    var layers by remember { mutableStateOf(false) }
    val lines = remember { Animatable(0f) }
    val icon = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        layers = true
        launch {
            lines.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
        launch {
            icon.animateTo(1f, tween(440, delayMillis = 300, easing = FastOutSlowInEasing))
        }
        launch {
            word.animateTo(1f, tween(440, delayMillis = 560, easing = FastOutSlowInEasing))
        }
        launch {
            tag.animateTo(1f, tween(780, delayMillis = 900, easing = LinearEasing))
        }
        withFrameNanos { }
        kotlinx.coroutines.delay(900L + 780L)
        com.lantern.library.data.StartupTrace.mark("lore intro finished")
        onFinished()
    }
    Box(Modifier.fillMaxSize()) {
        if (layers) {
            Image(
                painterResource(R.drawable.lore_splash_lines),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = lines.value },
                contentScale = ContentScale.Crop
            )
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.38f))
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(148.dp)
                            .graphicsLayer { alpha = icon.value * 0.9f }
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(0.55f), Color.Transparent)
                                ),
                                CircleShape
                            )
                    )
                    Image(
                        painterResource(R.drawable.lore_splash_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .size(112.dp)
                            .graphicsLayer { alpha = icon.value },
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.height(18.dp))
                val lift = with(LocalDensity.current) { 10.dp.toPx() }
                Image(
                    painterResource(R.drawable.lore_splash_wordmark),
                    contentDescription = "Lore",
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .graphicsLayer {
                            alpha = word.value
                            translationY = (1f - word.value) * lift
                        },
                    contentScale = ContentScale.FillWidth
                )
                Spacer(Modifier.height(10.dp))
                BoxWithConstraints(
                    Modifier.fillMaxWidth(0.78f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val full = maxWidth
                    Box(
                        Modifier
                            .width(full * tag.value.coerceIn(0f, 1f))
                            .clip(RectangleShape)
                    ) {
                        Image(
                            painterResource(R.drawable.lore_splash_tagline),
                            contentDescription = null,
                            modifier = Modifier.width(full),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
                Spacer(Modifier.weight(0.42f))
            }
        }
    }
}
