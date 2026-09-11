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
import androidx.compose.ui.unit.sp
import com.lantern.library.data.InterestCatalog
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun InterestOnboardingScreen(
    theme: ReaderTheme,
    initial: List<String>,
    allowCancel: Boolean,
    onContinue: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    val selected = remember(initial) { mutableStateListOf<String>().also { it.addAll(initial) } }
    AuroraBackdrop(theme) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp, 28.dp, 20.dp, 28.dp)
        ) {
            Text("What do you love to read?", color = ink, fontFamily = Playfair, fontSize = 28.sp)
            Text(
                "Pick a few genres. For You is based only on these — not on books in your library.",
                color = mute,
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
                        Text(
                            interest.label,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (on) Coral else Color(0x66FFFFFF))
                                .clickable {
                                    if (on) selected.remove(interest.id) else selected.add(interest.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            color = if (on) Color.White else ink,
                            fontSize = 14.sp
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(28.dp))
            val canGo = selected.isNotEmpty()
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (canGo) Coral else Color(0x55FFFFFF))
                    .clickable(enabled = canGo) { onContinue(selected.toList()) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Continue", color = if (canGo) Color.White else mute, fontSize = 16.sp)
            }
            if (allowCancel) {
                Text(
                    "Cancel",
                    color = mute,
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
