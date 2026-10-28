package com.lantern.library.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.lantern.library.ui.theme.LoreAction
import com.lantern.library.ui.theme.LoreActionHi
import com.lantern.library.ui.theme.LoreGhost
import com.lantern.library.ui.theme.LorePill
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Periwinkle
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.Sky
import com.lantern.library.ui.theme.SlateHi
import com.lantern.library.ui.theme.SourceSerif
import com.lantern.library.ui.theme.nookPalette

/**
 * The nook's own backdrop, under the old name.
 *
 * Nothing in the app draws a flat aurora gradient any more — the room in Nook.kt replaced it —
 * but keeping the name means a screen that still calls it gets the room rather than a purple
 * wash, and nothing silently regresses to the old look.
 */
@Composable
fun AuroraBackdrop(theme: ReaderTheme, content: @Composable BoxScope.() -> Unit) {
    NookBackdrop(nookPalette(theme), content = content)
}

/**
 * A pane of glass, in the nook's palette.
 *
 * Kept as a thin name over [GlassSurface] so every screen written before the redesign — Explore,
 * the appearance editor, the toast — picks up the new glass without being rewritten. The old
 * signature said `dark`; the palette now owns the colours.
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, dark: Boolean = false, radius: Int = 22, content: @Composable BoxScope.() -> Unit) {
    GlassSurface(nookPalette(dark), modifier, shape = RoundedCornerShape(radius.dp), content = content)
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
fun CoverFace(
    title: String,
    localRes: Int?,
    pathOrUrl: String?,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    fallbacks: List<String> = emptyList()
) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.shadow(8.dp, shape).clip(shape).background(if (dark) SlateHi else Color(0xFFD8C8E8))) {
        when {
            localRes != null -> Image(painterResource(localRes), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !pathOrUrl.isNullOrBlank() || fallbacks.isNotEmpty() -> {
                var bmp by remember(pathOrUrl, fallbacks) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(pathOrUrl, fallbacks) { bmp = CoverCache.load(pathOrUrl, fallbacks) }
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
            Text(
                message.orEmpty(),
                Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp),
                color = nookPalette(false).ink,
                fontSize = 14.sp
            )
        }
    }
}

fun readerFont(id: String): FontFamily = when (id) { "garamond" -> Garamond; "arial" -> Inter; else -> SourceSerif }

@Composable
fun LoreActionButton(label: String, onClick: () -> Unit, mark: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(LoreAction, LoreActionHi)))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (!mark.isNullOrBlank()) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(mark, color = LoreAction, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LorePillButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(LorePill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
/**
 * The reader's chrome, in the page's own light.
 *
 * These used to be fixed dark greys, which is why a cream page in light mode had a black bar
 * sitting on it like something from another app. A bar over paper should be warm and nearly
 * opaque; a bar over a night page should be the nook's navy glass. [opacity] is the reader's own
 * chromeOpacity setting, so the bars still fade towards the page when the reader wants the text
 * to have the screen to itself.
 */
fun readerChromeBar(opacity: Float, dark: Boolean = true): Color {
    val t = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 0.5f
    val base = if (dark) Color(0xFF0E1424) else Color(0xFFF7EFE6)
    return base.copy(alpha = 0.40f + 0.58f * t)
}

fun readerChromePanel(opacity: Float, dark: Boolean = true): Color {
    val t = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 0.5f
    val base = if (dark) Color(0xFF141C30) else Color(0xFFFFFAF4)
    return base.copy(alpha = 0.55f + 0.44f * t)
}

@Composable
fun LoreGhostButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val stroke = if (selected) LoreGhost else LoreGhost.copy(alpha = 0.55f)
    Text(
        label,
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(LoreGhost.copy(alpha = 0.22f)) else Modifier)
            .border(1.5.dp, stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = if (selected) Color.White else LoreGhost,
        fontSize = 14.sp
    )
}
