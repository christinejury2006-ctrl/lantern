package com.lantern.library.data

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A running record of what account sync actually did, stage by stage.
 *
 * "Sync isn't working" is not a bug report anyone can act on, and the failure modes here are all
 * silent by nature: a token that never arrives, a snapshot that reads back null, a merge that is
 * never applied, a Firestore write rejected by rules. Each of those looks identical from the
 * outside — nothing changes on screen — so the app now writes down every stage as it happens and
 * shows the list to the reader (You → Sync diagnosis).
 *
 * The record is deliberately small and in memory only:
 *
 * - **Small.** The last [MAX] entries; a sync writes a handful of lines, not a log file.
 * - **In memory.** It is diagnostic, not analytics. Nothing is uploaded anywhere, and it goes away
 *   with the process, so an account name or file id never ends up persisted for its own sake.
 * - **Honest about failure.** [mark] is neutral (a stage was reached); [fail] is a stage that
 *   stopped the sync. The panel colours them differently so "signed in" and "snapshot not applied"
 *   cannot be confused.
 */
object SyncDiag {
    private const val MAX = 60

    data class Line(
        val at: Long,
        val stage: String,
        val detail: String,
        val ok: Boolean
    ) {
        fun render(): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
            return "$time  ${if (ok) "·" else "!"}  $stage" + if (detail.isBlank()) "" else " — $detail"
        }
    }

    private val entries = mutableStateListOf<Line>()

    /** A snapshot of the record, newest first, for the diagnostics panel. */
    val lines: List<Line> get() = entries.reversed()

    fun mark(stage: String, detail: String = "") = add(stage, detail, ok = true)

    fun fail(stage: String, detail: String = "") = add(stage, detail, ok = false)

    private fun add(stage: String, detail: String, ok: Boolean) {
        entries.add(Line(System.currentTimeMillis(), stage, detail, ok))
        while (entries.size > MAX) entries.removeAt(0)
    }

    fun clear() = entries.clear()

    /** The whole record as plain text, for the Copy button. */
    fun report(): String = buildString {
        appendLine("Lore sync diagnosis")
        appendLine("Generated " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        if (entries.isEmpty()) {
            appendLine("No sync has been attempted yet on this install.")
        } else {
            entries.reversed().forEach { appendLine(it.render()) }
        }
    }
}
