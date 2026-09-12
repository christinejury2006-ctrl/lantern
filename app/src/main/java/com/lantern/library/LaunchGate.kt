package com.lantern.library

import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
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
    val sky = remember { Animatable(0f) }
    val ground = remember { Animatable(0f) }
    val icon = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        layers = true
        launch { sky.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { ground.animateTo(1f, tween(700, delayMillis = 220, easing = FastOutSlowInEasing)) }
        launch { icon.animateTo(1f, tween(500, delayMillis = 780, easing = FastOutSlowInEasing)) }
        launch { word.animateTo(1f, tween(480, delayMillis = 1180, easing = FastOutSlowInEasing)) }
        launch { tag.animateTo(1f, tween(1700, delayMillis = 1580, easing = LinearEasing)) }
        kotlinx.coroutines.delay(1580L + 1700L)
        com.lantern.library.data.StartupTrace.mark("lore intro finished")
        onFinished()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artAspect = 704f / 1520f
        val screen = maxWidth / maxHeight
        val aw = if (screen > artAspect) maxHeight * artAspect else maxWidth
        val ah = if (screen > artAspect) maxHeight else maxWidth / artAspect
        Box(
            Modifier
                .align(Alignment.Center)
                .size(aw, ah)
        ) {
            if (layers) {
                Image(
                    painterResource(R.drawable.lore_splash_lines_sky),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = sky.value },
                    contentScale = ContentScale.Fit
                )
                Image(
                    painterResource(R.drawable.lore_splash_lines_ground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = ground.value },
                    contentScale = ContentScale.Fit
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
                                .graphicsLayer { alpha = icon.value * 0.85f }
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color.White.copy(0.5f), Color.Transparent)
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
                    val lift = with(LocalDensity.current) { 8.dp.toPx() }
                    Image(
                        painterResource(R.drawable.lore_splash_wordmark),
                        contentDescription = "Lore",
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .graphicsLayer {
                                alpha = word.value
                                translationY = (1f - word.value) * lift
                            },
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(12.dp))
                    WrittenTagline(
                        progress = tag.value,
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .aspectRatio(TaglineGlyph.W / TaglineGlyph.H)
                    )
                    Spacer(Modifier.weight(0.42f))
                }
            }
        }
    }
}

@Composable
private fun WrittenTagline(progress: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.lore_splash_tagline)
    }
    val strokes = remember {
        TaglineGlyph.strokes.map { coords ->
            val p = Path()
            var i = 0
            var first = true
            while (i + 1 < coords.size) {
                val x = coords[i]
                val y = coords[i + 1]
                if (first) {
                    p.moveTo(x, y)
                    first = false
                } else {
                    p.lineTo(x, y)
                }
                i += 2
            }
            p
        }
    }
    val measures = remember(strokes) {
        strokes.map { PathMeasure(it.asAndroidPath(), false) }
    }
    val totalLen = remember(measures) { measures.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(1f) }
    Canvas(
        modifier.height(28.dp)
    ) {
        if (bitmap == null) return@Canvas
        val dstW = size.width
        val dstH = size.width * (TaglineGlyph.H / TaglineGlyph.W)
        val top = (size.height - dstH) / 2f
        val sx = dstW / TaglineGlyph.W
        val sy = dstH / TaglineGlyph.H
        val reveal = AndroidPath()
        var remain = (progress.coerceIn(0f, 1f) * totalLen)
        measures.forEach { pm ->
            if (remain <= 0f) return@forEach
            val take = minOf(remain, pm.length)
            val piece = AndroidPath()
            pm.getSegment(0f, take, piece, true)
            reveal.addPath(piece)
            remain -= take
        }
        val matrix = android.graphics.Matrix().apply { setScale(sx, sy) }
        reveal.transform(matrix)
        drawIntoCanvas { canvas ->
            val n = canvas.nativeCanvas
            val checkpoint = n.saveLayer(0f, top, dstW, top + dstH, null)
            n.drawBitmap(
                bitmap,
                android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
                android.graphics.RectF(0f, top, dstW, top + dstH),
                null
            )
            val mask = AndroidPaint().apply {
                isAntiAlias = true
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                strokeJoin = AndroidPaint.Join.ROUND
                strokeWidth = dstH * 0.62f
                color = android.graphics.Color.WHITE
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            n.drawPath(reveal, mask)
            n.restoreToCount(checkpoint)
        }
        val unused = Offset.Zero
        unused.x
    }
}
