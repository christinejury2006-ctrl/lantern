package com.lantern.library.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.BookFormat
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.StudioPhase
import com.lantern.library.data.StudioSession
import com.lantern.library.studio.PageKind
import com.lantern.library.studio.WebBlockType
import com.lantern.library.studio.WebDraft
import com.lantern.library.studio.WebPhase
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun StudioScreen(
    theme: ReaderTheme,
    session: StudioSession?,
    web: WebDraft?,
    onAddFile: () -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: () -> Unit,
    onAnalyze: (String) -> Unit,
    onWebKeep: (String) -> Unit,
    onWebRemove: (String) -> Unit,
    onWebCancel: () -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    val filePhase = session?.phase ?: StudioPhase.Idle
    AuroraBackdrop(theme) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp, 18.dp, 18.dp, 96.dp)
        ) {
            Text("Studio", color = ink, fontFamily = Playfair, fontSize = 30.sp)
            Text(
                "Turn webpages into books.",
                color = mute,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            when {
                web != null -> WebPane(web, dark, ink, mute, onWebKeep, onWebRemove, onWebCancel)
                filePhase == StudioPhase.Extracting -> Text(
                    "Reading this book…",
                    color = mute,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
                filePhase == StudioPhase.Review || filePhase == StudioPhase.Failed || filePhase == StudioPhase.Committing -> {
                    FileReviewCard(session!!, dark, ink, mute, filePhase == StudioPhase.Committing, onSend, onCancel)
                }
                else -> {
                    WebEntry(dark, ink, mute, onAnalyze)
                    GlassCard(Modifier.fillMaxWidth().padding(top = 14.dp).clickable(onClick = onAddFile), dark, 18) {
                        Text(
                            "Add EPUB or PDF",
                            color = ink,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebEntry(dark: Boolean, ink: Color, mute: Color, onAnalyze: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    GlassCard(Modifier.fillMaxWidth().padding(top = 20.dp), dark, 18) {
        Column(Modifier.padding(16.dp)) {
            Text("Create book from web", color = ink, fontFamily = Playfair, fontSize = 18.sp)
            Text("Paste one https page to analyze.", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                textStyle = TextStyle(color = ink, fontSize = 15.sp),
                cursorBrush = SolidColor(ink),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                decorationBox = { inner ->
                    if (url.isEmpty()) Text("https://", color = mute, fontSize = 15.sp)
                    inner()
                }
            )
        }
    }
    GlassCard(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable(enabled = url.isNotBlank()) { onAnalyze(url) },
        dark,
        18
    ) {
        Text(
            "Analyze page",
            color = if (url.isNotBlank()) Coral else mute,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun WebPane(
    web: WebDraft,
    dark: Boolean,
    ink: Color,
    mute: Color,
    onKeep: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCancel: () -> Unit
) {
    when (web.phase) {
        WebPhase.Fetching -> Text(
            "Analyzing page…",
            color = mute,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 24.dp)
        )
        WebPhase.Failed -> {
            Text(web.error ?: "Could not analyze that page.", color = Coral, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
            CancelRow(dark, ink, mute, false, onCancel)
        }
        WebPhase.Ready -> {
            val kind = if (web.kind == PageKind.Images) "Images" else "Text"
            Text(web.title.ifBlank { "Untitled page" }, color = ink, fontFamily = Playfair, fontSize = 22.sp, modifier = Modifier.padding(top = 18.dp))
            Text(kind, color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            val keptText = web.kept.filter { it.type == WebBlockType.Text }
            val keptImg = web.kept.filter { it.type == WebBlockType.Image }
            val unsure = web.unsure
            if (keptText.isNotEmpty()) {
                FieldLabel("Likely text", mute)
                keptText.take(8).forEach { c ->
                    Text(c.text.take(500), color = ink, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            if (web.kind == PageKind.Images || keptImg.isNotEmpty()) {
                FieldLabel("Likely pages", mute)
                if (keptImg.isEmpty()) {
                    Text("No clear content images yet. Check Unsure below.", color = mute, fontSize = 13.sp)
                } else {
                    keptImg.forEach { c ->
                        CoverFace(
                            "Page",
                            null,
                            c.localPath ?: c.imageUrl,
                            Modifier.fillMaxWidth().padding(bottom = 10.dp).aspectRatio(0.7f),
                            dark
                        )
                    }
                }
            }
            FieldLabel("Unsure — you decide", mute)
            if (unsure.isEmpty()) {
                Text("Nothing uncertain on this page.", color = mute, fontSize = 13.sp)
            } else {
                unsure.forEach { c ->
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), dark, 16) {
                        Column(Modifier.padding(14.dp)) {
                            Text(c.reason, color = mute, fontSize = 12.sp)
                            if (c.type == WebBlockType.Text) {
                                Text(c.text.take(400), color = ink, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                CoverFace(
                                    "Maybe content",
                                    null,
                                    c.localPath ?: c.imageUrl,
                                    Modifier.fillMaxWidth().padding(top = 8.dp).aspectRatio(0.75f),
                                    dark
                                )
                            }
                            Row(Modifier.padding(top = 10.dp)) {
                                Text("Keep", color = Coral, fontSize = 14.sp, modifier = Modifier.clickable { onKeep(c.id) }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp))
                                Text("Remove", color = ink, fontSize = 14.sp, modifier = Modifier.clickable { onRemove(c.id) }.padding(top = 4.dp, bottom = 4.dp))
                            }
                        }
                    }
                }
            }
            CancelRow(dark, ink, mute, false, onCancel)
        }
    }
}

@Composable
private fun FileReviewCard(
    session: StudioSession,
    dark: Boolean,
    ink: Color,
    mute: Color,
    busy: Boolean,
    onSend: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(session.workingPath) { mutableStateOf(session.title) }
    var author by remember(session.workingPath) { mutableStateOf(session.author) }
    val formatLabel = when (session.format) {
        BookFormat.EPUB -> "EPUB"
        BookFormat.PDF -> "PDF"
        else -> "Book"
    }
    Spacer(Modifier.height(20.dp))
    CoverFace(title.ifBlank { "Cover" }, null, session.coverPath, Modifier.width(120.dp).aspectRatio(0.68f), dark)
    if (session.coverPath.isNullOrBlank()) {
        Text("No cover found.", color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    FieldLabel("Title", mute)
    GlassCard(Modifier.fillMaxWidth(), dark, 16) {
        BasicTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            enabled = !busy,
            textStyle = TextStyle(color = ink, fontSize = 16.sp),
            cursorBrush = SolidColor(ink),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
        )
    }
    FieldLabel("Author", mute)
    GlassCard(Modifier.fillMaxWidth(), dark, 16) {
        BasicTextField(
            value = author,
            onValueChange = { author = it },
            singleLine = true,
            enabled = !busy,
            textStyle = TextStyle(color = ink, fontSize = 16.sp),
            cursorBrush = SolidColor(ink),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
            decorationBox = { inner ->
                if (author.isEmpty()) Text("Author", color = mute, fontSize = 16.sp)
                inner()
            }
        )
    }
    Text(
        "$formatLabel  ·  ${session.pageCount} page${if (session.pageCount == 1) "" else "s"}",
        color = mute,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 14.dp)
    )
    if (!session.warning.isNullOrBlank()) {
        Text(session.warning, color = Coral, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
    FieldLabel("Contents", mute)
    when {
        session.toc.isNotEmpty() -> session.toc.forEach { entry ->
            Text(entry.title, color = ink, fontSize = 13.sp, modifier = Modifier.padding(start = (entry.level * 12).dp, top = 4.dp, bottom = 2.dp))
        }
        session.chapterTitles.isNotEmpty() -> session.chapterTitles.forEach { name ->
            Text(name, color = ink, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        }
        !session.tocNote.isNullOrBlank() -> Text(session.tocNote, color = mute, fontSize = 13.sp)
    }
    val canSend = session.workingPath != null && !busy
    GlassCard(Modifier.fillMaxWidth().padding(top = 22.dp).clickable(enabled = canSend) { onSend(title, author) }, dark, 18) {
        Text(if (busy) "Adding…" else "Send to Library", color = if (canSend) Coral else mute, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
    }
    CancelRow(dark, ink, mute, busy, onCancel)
}

@Composable
private fun CancelRow(dark: Boolean, ink: Color, mute: Color, busy: Boolean, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(enabled = !busy, onClick = onCancel), dark, 18) {
        Text("Cancel", color = ink, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
    }
}

@Composable
private fun FieldLabel(text: String, mute: Color) {
    Text(text, color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}
