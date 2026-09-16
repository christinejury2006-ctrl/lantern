package com.lantern.library.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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

@Composable
fun AppearanceLayer(
    look: AppearLook,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    var bmp by remember(look.wallpaperId, look.wallpaperUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(look.wallpaperId, look.wallpaperUrl) {
        bmp = withContext(Dispatchers.IO) {
            WallpaperStore.load(context, look.wallpaperId, look.wallpaperUrl)
        }
    }
    val hasWp = look.hasWallpaper && look.wallpaperOpacity > 0.02f && bmp != null
    val wash = if (hasWp) (0.22f + look.overlay * 0.55f).coerceIn(0.18f, 0.82f) else 1f
    val start = Color(look.ombreStartArgb.toInt())
    val end = Color(look.ombreEndArgb.toInt())
    val solid = Color(look.solidArgb.toInt())
    Box(modifier.fillMaxSize()) {
        if (hasWp) {
            Image(
                bmp!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = look.wallpaperOpacity.coerceIn(0f, 1f) },
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = wash }
                .then(
                    if (look.fill == AppearFill.Solid) Modifier.background(solid)
                    else Modifier.background(Brush.verticalGradient(listOf(start, end)))
                )
        )
        if (hasWp) {
            val veil = if (look.fill == AppearFill.Solid) solid else Color.White
            Box(
                Modifier
                    .fillMaxSize()
                    .background(veil.copy(alpha = (look.overlay * 0.18f).coerceIn(0f, 0.28f)))
            )
        }
        content()
    }
}
