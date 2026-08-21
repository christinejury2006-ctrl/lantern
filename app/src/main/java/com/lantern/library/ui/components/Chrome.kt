package com.lantern.library.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CoverCache
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.theme.Aqua
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Garamond
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Inter
import com.lantern.library.ui.theme.Lilac
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Periwinkle
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.Sky
import com.lantern.library.ui.theme.SlateHi
import com.lantern.library.ui.theme.SourceSerif

fun auroraBrush(dark: Boolean) = if (dark) Brush.verticalGradient(listOf(Color(0xFF3E4A5C), Color(0xFF4A5668), Color(0xFF3A4454)))
else Brush.verticalGradient(listOf(Lilac, Periwinkle, Sky, Aqua))

@Composable
fun AuroraBackdrop(theme: ReaderTheme, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(auroraBrush(theme == ReaderTheme.DARK)), content = content)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, dark: Boolean = false, radius: Int = 22, content: @Composable BoxScope.() -> Unit) {
    val fill = if (dark) Color(0x664A5568) else Color(0x99FFFFFF)
    Box(modifier.shadow(10.dp, RoundedCornerShape(radius.dp), spotColor = Color(0x33000000)).clip(RoundedCornerShape(radius.dp)).background(fill).border(1.dp, if (dark) Color(0x33FFFFFF) else Color(0x66FFFFFF), RoundedCornerShape(radius.dp)), content = content)
}

@Composable
fun BookCover(book: LibraryBook, modifier: Modifier = Modifier, dark: Boolean = false) {
    CoverFace(book.title, book.localCover, book.remoteCover, modifier, dark)
}

@Composable
fun ReadingProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val filled = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
    ) {
        if (filled > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(filled)
                    .background(Brush.horizontalGradient(listOf(Aqua, Lilac, Coral)))
            )
        }
    }
}

@Composable
fun CoverFace(title: String, localRes: Int?, pathOrUrl: String?, modifier: Modifier = Modifier, dark: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.shadow(8.dp, shape).clip(shape).background(if (dark) SlateHi else Color(0xFFD8C8E8))) {
        when {
            localRes != null -> Image(painterResource(localRes), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !pathOrUrl.isNullOrBlank() -> {
                var bmp by remember(pathOrUrl) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(pathOrUrl) { bmp = CoverCache.load(pathOrUrl) }
                if (bmp != null) Image(bmp!!.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("Lore", Modifier.align(Alignment.Center).padding(8.dp), color = if (dark) NightText else Ink, fontFamily = Playfair, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
            }
            else -> Text("Lore", Modifier.align(Alignment.Center).padding(8.dp), color = if (dark) NightText else Ink, fontFamily = Playfair, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
fun FadeToast(message: String?) {
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        GlassCard(Modifier.fillMaxWidth(), radius = 16) {
            Text(message.orEmpty(), Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp), color = Ink, fontSize = 14.sp)
        }
    }
}

fun readerFont(id: String): FontFamily = when (id) { "garamond" -> Garamond; "arial" -> Inter; else -> SourceSerif }
