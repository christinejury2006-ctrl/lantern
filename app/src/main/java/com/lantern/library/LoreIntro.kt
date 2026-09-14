package com.lantern.library

import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lantern.library.data.LanternStore
import com.lantern.library.data.StartupTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private val PosterOmbre = Brush.verticalGradient(
    listOf(
        Color(0xFFC29BFB),
        Color(0xFFBE9EFB),
        Color(0xFFB5A5FB),
        Color(0xFFA8AAFA),
        Color(0xFF99BEF5),
        Color(0xFF82C9EF),
        Color(0xFF76D7E8),
        Color(0xFF71DCE6)
    )
)

private const val TOTAL_MS = 3450f
private val DST_IN = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
private val DST_OUT = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

@Composable
internal fun LoreAfterSplash() {
    var introDone by remember { mutableStateOf(false) }
    var storeAllowed by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }
    Box(Modifier.fillMaxSize()) {
        if (storeAllowed) {
            val store: LanternStore = viewModel()
            LaunchedEffect(introDone, store.hydrated) {
                if (introDone && store.hydrated && fade.value == 0f) {
                    fade.animateTo(1f, tween(280))
                }
            }
            if (fade.value > 0f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
                    LanternRoot(store)
                }
            }
        }
        if (fade.value < 1f) {
            LorePosterScene(
                onFirstFrame = { storeAllowed = true },
                onFinished = { introDone = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fade.value }
            )
        }
    }
}

@Composable
private fun LorePosterScene(
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    remember {
        StartupTrace.mark("LoreIntro initialization")
        true
    }
    val context = LocalContext.current
    val clock = remember { Animatable(0f) }
    val strokes = remember {
        TaglineGlyph.strokes.map { coords ->
            val p = AndroidPath()
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
        strokes.map { PathMeasure(it, false) }
    }
    val totalLen = remember(measures) {
        measures.sumOf { it.length.toDouble() }.toFloat().coerceAtLeast(1f)
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { LorePoster.decode(context) }
        withFrameNanos { }
        StartupTrace.mark("first Lore custom frame")
        onFirstFrame()
        clock.animateTo(1f, tween(TOTAL_MS.toInt(), easing = LinearEasing))
        StartupTrace.mark("lore intro finished")
        onFinished()
    }
    val body = LorePoster.body
    val sparkles = LorePoster.sparkles
    val ms = clock.value * TOTAL_MS
    Canvas(modifier) {
        val dst = fitRect(size.width, size.height, LorePoster.W.toFloat(), LorePoster.H.toFloat())
        val ombreT = smooth(((ms - 0f) / 400f).coerceIn(0f, 1f))
        val sceneT = smooth(((ms - 250f) / 1150f).coerceIn(0f, 1f))
        val iconT = smooth(((ms - 950f) / 600f).coerceIn(0f, 1f))
        val wordT = smooth(((ms - 1350f) / 600f).coerceIn(0f, 1f))
        val writeT = ((ms - 1850f) / 1400f).coerceIn(0f, 1f)
        val sparkT = smooth(((ms - 3150f) / 300f).coerceIn(0f, 1f))

        drawRect(Color(0xFFC8A1F3))
        drawRect(PosterOmbre, alpha = ombreT)

        if (body == null) return@Canvas
        val src = Rect(0, 0, body.width, body.height)
        val scale = dst.width() / LorePoster.W
        drawIntoCanvas { canvas ->
            val n = canvas.nativeCanvas
            val bmpPaint = AndroidPaint().apply { isFilterBitmap = true; isAntiAlias = true }

            if (sceneT > 0f) {
                val layer = n.saveLayer(dst, null)
                n.drawBitmap(body, src, dst, bmpPaint)
                val gp = AndroidPaint().apply {
                    isAntiAlias = true
                    shader = LinearGradient(
                        dst.right, dst.top, dst.left, dst.bottom,
                        intArrayOf(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, (sceneT * 0.82f).coerceIn(0f, 1f), sceneT.coerceIn(0.001f, 1f)),
                        Shader.TileMode.CLAMP
                    )
                    xfermode = DST_IN
                }
                n.drawRect(dst, gp)
                val hole = AndroidPaint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.BLACK
                    xfermode = DST_OUT
                }
                n.drawRoundRect(mapRect(330f, 655f, 525f, 868f, dst, scale), 48f * scale, 48f * scale, hole)
                n.drawRect(mapRect(130f, 885f, 720f, 1135f, dst, scale), hole)
                n.drawRect(mapRect(145f, 1135f, 715f, 1225f, dst, scale), hole)
                n.restoreToCount(layer)
            }

            if (iconT > 0f) {
                val ir = mapRect(318f, 640f, 538f, 882f, dst, scale)
                val layer = n.saveLayer(ir, null)
                n.drawBitmap(body, src, dst, bmpPaint)
                val radius = (ir.width().coerceAtLeast(ir.height()) * 0.62f) * (0.35f + 0.65f * iconT)
                val mask = AndroidPaint().apply {
                    isAntiAlias = true
                    shader = RadialGradient(
                        ir.centerX(), ir.centerY(), radius.coerceAtLeast(1f),
                        intArrayOf(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.72f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    xfermode = DST_IN
                }
                n.drawRect(ir, mask)
                n.restoreToCount(layer)
            }

            if (wordT > 0f) {
                val wr = mapRect(130f, 885f, 720f, 1135f, dst, scale)
                val layer = n.saveLayer(wr, null)
                n.drawBitmap(body, src, dst, bmpPaint)
                val gp = AndroidPaint().apply {
                    isAntiAlias = true
                    shader = LinearGradient(
                        wr.left, wr.centerY(), wr.right, wr.centerY(),
                        intArrayOf(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, (wordT * 0.88f).coerceIn(0f, 1f), wordT.coerceIn(0.001f, 1f)),
                        Shader.TileMode.CLAMP
                    )
                    xfermode = DST_IN
                }
                n.drawRect(wr, gp)
                n.restoreToCount(layer)
            }

            if (writeT > 0f) {
                val tr = mapRect(145f, 1135f, 715f, 1225f, dst, scale)
                val layer = n.saveLayer(tr, null)
                n.drawBitmap(body, src, dst, bmpPaint)
                val reveal = AndroidPath()
                var remain = writeT * totalLen
                measures.forEach { pm ->
                    if (remain <= 0f) return@forEach
                    val take = min(remain, pm.length)
                    val piece = AndroidPath()
                    pm.getSegment(0f, take, piece, true)
                    reveal.addPath(piece)
                    remain -= take
                }
                val mx = android.graphics.Matrix()
                mx.setScale(scale, scale)
                mx.postTranslate(dst.left, dst.top)
                reveal.transform(mx)
                val mask = AndroidPaint().apply {
                    isAntiAlias = true
                    style = AndroidPaint.Style.STROKE
                    strokeCap = AndroidPaint.Cap.ROUND
                    strokeJoin = AndroidPaint.Join.ROUND
                    strokeWidth = 14f * scale
                    color = android.graphics.Color.WHITE
                    xfermode = DST_IN
                }
                n.drawPath(reveal, mask)
                n.restoreToCount(layer)
            }

            if (sparkT > 0f && sparkles != null) {
                val sp = AndroidPaint().apply {
                    isFilterBitmap = true
                    isAntiAlias = true
                    alpha = (sparkT * 255f).toInt().coerceIn(0, 255)
                }
                n.drawBitmap(sparkles, src, dst, sp)
            }
        }
    }
}

private fun fitRect(dw: Float, dh: Float, aw: Float, ah: Float): RectF {
    val s = min(dw / aw, dh / ah)
    val w = aw * s
    val h = ah * s
    val l = (dw - w) / 2f
    val t = (dh - h) / 2f
    return RectF(l, t, l + w, t + h)
}

private fun mapRect(l: Float, t: Float, r: Float, b: Float, dst: RectF, scale: Float): RectF {
    return RectF(
        dst.left + l * scale,
        dst.top + t * scale,
        dst.left + r * scale,
        dst.top + b * scale
    )
}

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
