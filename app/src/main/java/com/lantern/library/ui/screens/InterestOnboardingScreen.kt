package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lantern.library.data.InterestCatalog
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette

@Composable
fun InterestOnboardingScreen(
    theme: ReaderTheme,
    initial: List<String>,
    allowCancel: Boolean,
    onContinue: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    val palette = nookPalette(theme)
    val selected = remember(initial) { mutableStateListOf<String>().also { it.addAll(initial) } }
    NookBackdrop(palette) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp, 28.dp, 20.dp, 28.dp)
        ) {
            Text("What do you love to read?", color = palette.ink, fontFamily = Playfair, fontSize = 28.sp)
            Text(
                "Pick a few genres. Editor's Choice is based only on these — not on books in your library.",
                color = palette.mute,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            InterestCatalog.all.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { interest ->
                        val on = interest.id in selected
                        GlassSurface(
                            palette,
                            Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                            fill = if (on) palette.accent else palette.glass,
                            onClick = {
                                if (on) selected.remove(interest.id) else selected.add(interest.id)
                            }
                        ) {
                            Text(
                                interest.label,
                                color = if (on) palette.accentInk else palette.ink,
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp)
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(28.dp))
            val canGo = selected.isNotEmpty()
            GlassSurface(
                palette,
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                fill = if (canGo) palette.accent else palette.glass,
                onClick = { if (canGo) onContinue(selected.toList()) }
            ) {
                Text(
                    if (canGo) "Continue" else "Choose at least one",
                    color = if (canGo) palette.accentInk else palette.faint,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(vertical = 15.dp)
                )
            }
            if (allowCancel) {
                Text(
                    "Cancel",
                    color = palette.mute,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 14.dp)
                        .clickable(onClick = onCancel)
                )
            }
        }
    }
}
