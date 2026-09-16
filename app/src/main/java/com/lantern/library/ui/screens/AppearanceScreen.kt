package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.AppearFill
import com.lantern.library.data.AppearLook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.WallpaperCatalog
import com.lantern.library.data.appearSwatches
import com.lantern.library.ui.components.AppearanceLayer
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun AppearanceEditor(
    title: String,
    theme: ReaderTheme,
    look: AppearLook,
    onChange: (AppearLook) -> Unit,
    onDone: () -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    val gold = Color(0xFFE8D9A8)
    AppearanceLayer(look) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp, 18.dp, 18.dp, 96.dp)
        ) {
            Text(title, color = ink, fontFamily = Playfair, fontSize = 28.sp)
            Text("Live preview is this screen.", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth().height(132.dp), dark, 18) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
                    AppearanceLayer(look) {
                        Column(Modifier.padding(16.dp)) {
                            Text("My Library", color = ink, fontFamily = Playfair, fontSize = 18.sp)
                            Text("Wallpaper under a soft wash.", color = mute, fontSize = 12.sp)
                        }
                    }
                }
            }
            Field("Background", mute)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF))) {
                FillChip("Solid", look.fill == AppearFill.Solid, ink) {
                    onChange(look.copy(fill = AppearFill.Solid))
                }
                FillChip("Ombre", look.fill == AppearFill.Ombre, ink) {
                    onChange(look.copy(fill = AppearFill.Ombre))
                }
            }
            if (look.fill == AppearFill.Solid) {
                Field("Colour", mute)
                SwatchRow(look.solidArgb, ink) { onChange(look.copy(solidArgb = it)) }
            } else {
                Field("Ombre start", mute)
                SwatchRow(look.ombreStartArgb, ink) { onChange(look.copy(ombreStartArgb = it)) }
                Field("Ombre end", mute)
                SwatchRow(look.ombreEndArgb, ink) { onChange(look.copy(ombreEndArgb = it)) }
            }
            Field("Wallpaper", mute)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(64.dp, 88.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x55FFFFFF))
                            .border(
                                2.dp,
                                if (!look.hasWallpaper) Coral else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onChange(look.copy(wallpaperId = null, wallpaperUrl = null)) },
                        contentAlignment = Alignment.Center
                    ) { Text("None", color = ink, fontSize = 11.sp) }
                }
                WallpaperCatalog.items.forEach { item ->
                    val on = look.wallpaperId == item.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverFace(
                            item.label, null, item.url,
                            Modifier
                                .width(64.dp)
                                .aspectRatio(0.72f)
                                .border(2.dp, if (on) Coral else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    onChange(look.copy(wallpaperId = item.id, wallpaperUrl = item.url))
                                },
                            dark
                        )
                        Text(item.label, color = if (on) Coral else mute, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Field("Wallpaper opacity", mute)
            Slider(
                value = look.wallpaperOpacity,
                onValueChange = { onChange(look.copy(wallpaperOpacity = it)) },
                enabled = look.hasWallpaper,
                colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
            )
            Field("Translucent overlay", mute)
            Slider(
                value = look.overlay,
                onValueChange = { onChange(look.copy(overlay = it)) },
                colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
            )
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onDone), dark, 18) {
                Text("Done", color = Coral, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun Field(text: String, mute: Color) {
    Text(text, color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun FillChip(label: String, on: Boolean, ink: Color, click: () -> Unit) {
    Text(
        label,
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Color(0xFFE8D9A8) else Color.Transparent)
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (on) Color(0xFF2B2430) else ink,
        fontSize = 14.sp
    )
}

@Composable
private fun SwatchRow(selected: Long, ink: Color, onPick: (Long) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        appearSwatches.forEach { argb ->
            val on = selected == argb
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(if (on) 2.dp else 1.dp, if (on) Coral else ink.copy(0.25f), CircleShape)
                    .clickable { onPick(argb) }
            )
        }
    }
}
