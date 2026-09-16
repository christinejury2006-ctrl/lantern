package com.lantern.library.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.lantern.library.data.AppearFill
import com.lantern.library.data.AppearLook
import com.lantern.library.data.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun argbColor(argb: Long): Color = Color((argb and 0xFFFFFFFFL).toInt())

@Composable
fun AppearanceLayer(
    look: AppearLook,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var bmp by remember(look.wallpaperId, look.wallpaperUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(look.wallpaperId, look.wallpaperUrl) {
        bmp = withContext(Dispatchers.IO) {
            runCatching { WallpaperStore.load(context, look.wallpaperId, look.wallpaperUrl) }.getOrNull()
        }
    }
    val paper = bmp
    val opacity = if (look.wallpaperOpacity.isFinite()) look.wallpaperOpacity.coerceIn(0f, 1f) else 0.32f
    val overlay = if (look.overlay.isFinite()) look.overlay.coerceIn(0f, 1f) else 0.28f
    val hasWp = look.hasWallpaper && opacity > 0.02f && paper != null
    val wash = if (hasWp) (0.22f + overlay * 0.55f).coerceIn(0.18f, 0.82f) else 1f
    val start = argbColor(look.ombreStartArgb)
    val end = argbColor(look.ombreEndArgb)
    val solid = argbColor(look.solidArgb)
    Box(modifier.fillMaxSize()) {
        if (hasWp && paper != null && !paper.isRecycled) {
            Image(
                paper.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity },
                contentScale = ContentScale.Crop
            )
        }
        val fillMod = if (look.fill == AppearFill.Solid) {
            Modifier.background(solid)
        } else {
            Modifier.background(Brush.verticalGradient(listOf(start, end)))
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = wash }
                .then(fillMod)
        )
        if (hasWp) {
            val veil = if (look.fill == AppearFill.Solid) solid else Color.White
            Box(
                Modifier
                    .fillMaxSize()
                    .background(veil.copy(alpha = (overlay * 0.18f).coerceIn(0f, 0.28f)))
            )
        }
        content()
    }
}
