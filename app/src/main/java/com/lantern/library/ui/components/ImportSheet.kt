package com.lantern.library.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lantern.library.data.BookFormat
import com.lantern.library.data.ImportPlan
import com.lantern.library.data.ImportProgress
import com.lantern.library.data.ImportState
import com.lantern.library.data.ImportSummary
import com.lantern.library.data.Transfers
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair

/**
 * The review sheet for a multi-file import.
 *
 * The picker can hand back two hundred files at once, and copying two hundred files on the strength
 * of a filename is how a library fills up with two copies of the same book. So the sheet shows what
 * was actually measured — real name, real size, real format — marks the duplicates before anything
 * is copied, and then reports each file's own outcome while the others carry on.
 *
 * Two things here exist because of a real two-hundred-book batch:
 *
 * - **The list is lazy and keyed by URI.** A `Column` with a `.forEach` built every row, every
 *   frame, for the whole selection; at two hundred rows that is thousands of composables per
 *   progress update. `LazyColumn` composes the ten rows on screen.
 * - **It can be stopped.** "Cancel" hides the sheet but the batch keeps copying; "Stop" ends the
 *   batch after the file in hand, and the rows already added stay added. Both are offered, and they
 *   say what they do.
 *
 * It stays open after an import finishes on purpose: the reader needs to see which file failed and
 * where the Retry button is, and a toast cannot hold a button.
 */
@Composable
fun ImportReviewSheet(
    plan: ImportPlan,
    rows: List<ImportProgress>,
    busy: Boolean,
    measured: Int,
    toMeasure: Int,
    running: Boolean,
    palette: NookPalette,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    // The batch's own denominator is the number of files it will actually attempt.
    val summary = ImportSummary.of(rows, total = if (plan.readyCount > 0) plan.readyCount else plan.total)
    val byUri = rows.associateBy { it.uri }
    Dialog(onDismissRequest = { onDismiss() }) {
        GlassSurface(
            palette,
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            fill = if (palette.dark) Color(0xF20E1424) else Color(0xF7FFFFFF)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Import books", color = palette.ink, fontFamily = Playfair, fontSize = 22.sp)
                Text(
                    when {
                        busy -> "Reading ${measured.coerceAtMost(toMeasure)} of $toMeasure files…"
                        rows.isNotEmpty() -> summary.line()
                        else -> plan.summary()
                    },
                    color = palette.mute,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!busy && plan.overflowCount > 0) {
                    // Named before the first byte is copied, not discovered at the end of the run.
                    Text(
                        "${plan.overflowCount} of these will not fit — your library holds " +
                            "200 imported books.",
                        color = palette.accent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(plan.items, key = { it.candidate.uri }) { item ->
                        // Read the row for this file only: a progress update on file 37 must not
                        // recompose file 12.
                        val progress = byUri[item.candidate.uri]
                        ImportRowView(
                            palette = palette,
                            name = item.candidate.name,
                            note = progress?.let { row ->
                                row.message.ifBlank {
                                    when (row.state) {
                                        ImportState.WAITING -> "Waiting its turn"
                                        ImportState.COPYING -> "Copying…"
                                        ImportState.ADDED -> "Added to your library"
                                        ImportState.FAILED -> "Failed"
                                        ImportState.SKIPPED -> "Skipped"
                                        ImportState.STOPPED -> "Stopped"
                                    }
                                }
                            } ?: item.note(),
                            done = progress?.state == ImportState.ADDED,
                            failed = progress?.state == ImportState.FAILED,
                            stopped = progress?.state == ImportState.STOPPED,
                            removable = !running && progress == null && item.importable,
                            retryable = !running && progress?.state == ImportState.FAILED,
                            onRemove = { onRemove(item.candidate.uri) },
                            onRetry = { onRetry(item.candidate.uri) }
                        )
                    }
                    if (plan.items.isEmpty()) {
                        item {
                            Text(
                                "Nothing came back from the picker.",
                                color = palette.mute,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (plan.problemCount > 0 || plan.duplicateCount > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Only files marked ready are copied. A duplicate is matched by its bytes " +
                            "— size, opening and ending — not by its name, so renaming a file will " +
                            "not bring it in twice.",
                        color = palette.faint,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (running) {
                    Text(
                        "${summary.added} imported · ${summary.failed} failed · " +
                            "${summary.stopped} not reached",
                        color = palette.mute,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetButton(
                        palette,
                        if (running) "Stop" else "Close",
                        filled = false,
                        onClick = if (running) onStop else onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    SheetButton(
                        palette,
                        when {
                            running -> "Importing…"
                            plan.readyCount == 0 -> "Nothing to import"
                            summary.failed > 0 || summary.stopped > 0 -> "Retry remaining"
                            else -> "Import ${plan.readyCount} book${if (plan.readyCount == 1) "" else "s"}"
                        },
                        filled = true,
                        enabled = !running && !busy && plan.readyCount > 0,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** One file: what it is, what will happen to it, and the one action that applies to it. */
@Composable
private fun ImportRowView(
    palette: NookPalette,
    name: String,
    note: String,
    done: Boolean,
    failed: Boolean,
    stopped: Boolean,
    removable: Boolean,
    retryable: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = if (done || stopped) palette.mute else palette.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                note,
                color = when {
                    failed -> palette.accent
                    done || stopped -> palette.mute
                    else -> palette.faint
                },
                fontSize = 11.sp,
                maxLines = 2
            )
        }
        when {
            retryable -> SmallAction(palette, "Retry", onRetry)
            removable -> SmallAction(palette, "Remove", onRemove)
            done -> Text("✓", color = palette.accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SmallAction(palette: NookPalette, label: String, onClick: () -> Unit) {
    GlassSurface(palette, Modifier, shape = RoundedCornerShape(12.dp), onClick = onClick) {
        Text(
            label,
            color = palette.ink,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SheetButton(
    palette: NookPalette,
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        palette,
        modifier,
        shape = RoundedCornerShape(16.dp),
        fill = when {
            !enabled -> palette.glass
            filled -> palette.accent
            else -> Color.Transparent
        },
        onClick = if (enabled) onClick else null
    ) {
        Text(
            label,
            color = when {
                !enabled -> palette.faint
                filled -> palette.accentInk
                else -> palette.ink
            },
            fontSize = 13.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp)
        )
    }
}

/** Format label for a candidate, or "Unsupported" — used by callers that show a summary line. */
internal fun formatLabel(format: BookFormat?): String = when (format) {
    BookFormat.EPUB -> "EPUB"
    BookFormat.PDF -> "PDF"
    else -> "Unsupported"
}

/** "· 2.4 MB" for the summary line; empty when the size could not be measured. */
internal fun sizeLabel(bytes: Long): String = if (bytes > 0L) " · " + Transfers.size(bytes) else ""
