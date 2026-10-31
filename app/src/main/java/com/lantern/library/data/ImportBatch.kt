package com.lantern.library.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * What happened to one file.
 *
 * Deliberately small: the batch decides what the review sheet says from these four answers, so the
 * per-file work (copy, validate, commit) has nowhere to hide a second, different notion of success.
 */
sealed class ImportOutcome {
    /** Copied, validated, recorded. Its cloud upload is the upload queue's business now. */
    object Added : ImportOutcome()

    /** Not imported, and not a failure: a duplicate found late, or the file vanished. */
    data class Skipped(val reason: String) : ImportOutcome()

    /**
     * This file could not be imported. The rest of the batch is unaffected.
     *
     * A phone that runs out of storage lands here too, with a message naming the file, the space it
     * needed and the space that was free. It is deliberately *not* a batch-halting outcome: the next
     * files may be small enough to fit, and stopping the run on the reader's behalf would lose
     * books that could have been imported.
     */
    data class Failed(val reason: String) : ImportOutcome()
}

/** The per-file work of an import, injected so the batch can be tested without a device. */
interface ImportWorker {
    suspend fun import(item: ImportItem): ImportOutcome
}

/**
 * Runs an import of any size, one file at a time, with bounded memory and honest progress.
 *
 * Why one at a time, when the picker can hand over two hundred files:
 *
 * - **Memory.** Copying and validating one file at a time means the app's peak extra memory is one
 *   read buffer, not the whole selection. Nothing here ever holds a `ByteArray` of a book: the
 *   candidate is metadata, and the bytes go from the provider's stream straight to disk.
 * - **File descriptors.** Each file is opened once and closed before the next is opened. Two
 *   hundred `openInputStream` calls in parallel is how a provider runs out of descriptors and how
 *   the failure shows up as an unrelated `IOException` somewhere in the middle of the batch.
 * - **Isolation.** A failure is caught *per file* and turned into a row on the sheet. It cannot
 *   stop the loop, cancel the coroutine, or lose the fact that the other files succeeded.
 * - **Cancellation.** A stop request is checked between files and inside them (the worker polls the
 *   same flag while copying). Files already committed stay committed; files not reached are marked
 *   [ImportState.STOPPED], which is a truthful state and not a failure.
 *
 * The batch never touches the cloud. The existing three-slot upload pool takes imported books
 * afterwards, unchanged.
 */
class ImportBatch(private val worker: ImportWorker) {

    /**
     * Imports every importable item in [items], reporting each row as it settles.
     *
     * @param stopped polled between files; a true answer ends the batch cleanly.
     *
     * There is no count limit to check here, and this loop has no notion of one: a batch of a
     * thousand books is a thousand turns of the same loop, each holding one file's metadata and one
     * copy buffer. A file that does not fit on the phone is a [ImportOutcome.Failed] with its own
     * message, and the loop carries on with the next file.
     * @param report called for every state change of every row, on the batch's own thread.
     * @return the counts the sheet's summary line shows.
     */
    suspend fun run(
        items: List<ImportItem>,
        stopped: () -> Boolean = { false },
        report: suspend (ImportProgress) -> Unit
    ): ImportSummary {
        val rows = ArrayList<ImportProgress>(items.size)
        var halted = false
        var haltReason = "Stopped. The rest of this batch was not imported."

        // A plain loop, not `forEachIndexed`, because a halt must leave the remainder reported once
        // and then stop — not revisited on the next iteration.
        for (index in items.indices) {
            val item = items[index]
            val row = { state: ImportState, message: String ->
                ImportProgress(item.candidate.uri, item.candidate.name, state, message, index)
            }
            if (halted) break
            if (stopped() || !currentCoroutineContext().isActive) {
                halted = true
                haltReason = "Stopped. The rest of this batch was not imported."
                break
            }

            report(row(ImportState.COPYING, "Copying…"))
            val outcome: ImportOutcome = try {
                // A coroutine cancellation is the app going away, not a bad file. Stop the batch and
                // let it propagate, so structured concurrency keeps working — but first record that
                // the remaining files were not attempted, which is the honest state for the sheet.
                currentCoroutineContext().ensureActive()
                worker.import(item)
            } catch (cancelled: CancellationException) {
                // NonCancellable: the coroutine is already cancelled, so a plain `withContext` in a
                // reporter would throw before a single stopped row was published. The bookkeeping
                // has to happen even though the work does not.
                withContext(NonCancellable) {
                    rows += row(ImportState.STOPPED, "Stopped before this file was imported.")
                    report(rows.last())
                    rest(items, index + 1, "Stopped before this file was imported.", rows, report)
                }
                throw cancelled
            } catch (t: Throwable) {
                // Every other exception is this file's problem alone. It is described in plain words
                // rather than swallowed: a row that says nothing is worse than a row that says
                // "OutOfMemoryError".
                ImportOutcome.Failed(t.describe())
            }

            val settled = when (outcome) {
                ImportOutcome.Added -> row(ImportState.ADDED, "Added to your library")
                is ImportOutcome.Skipped -> row(ImportState.SKIPPED, outcome.reason)
                is ImportOutcome.Failed -> row(ImportState.FAILED, outcome.reason)
            }
            rows += settled
            report(settled)
        }
        // Everything after a halt is reported in one go, so the sheet ends up with a row per chosen
        // file rather than a list that silently stops halfway.
        if (halted) rest(items, rows.size, haltReason, rows, report)
        return ImportSummary.of(rows, total = items.size)
    }

    /** Reports every item from [from] onwards as stopped, without attempting it. */
    private suspend fun rest(
        items: List<ImportItem>,
        from: Int,
        reason: String,
        rows: MutableList<ImportProgress>,
        report: suspend (ImportProgress) -> Unit
    ) {
        for (i in from until items.size) {
            val row = ImportProgress(
                items[i].candidate.uri,
                items[i].candidate.name,
                ImportState.STOPPED,
                reason,
                i
            )
            rows += row
            report(row)
        }
    }

    /**
     * The reader's phrase for a failure.
     *
     * The type name is kept, because "OutOfMemoryError" and "FileNotFoundException" send a reader
     * (and a bug report) in two different directions, and a generic "could not import" sends them
     * nowhere.
     */
    private fun Throwable.describe(): String {
        val kind = when (this) {
            is java.io.FileNotFoundException -> "The file could not be opened — it may have been moved or its permission revoked."
            is SecurityException -> "This app no longer has permission to read that file."
            is java.io.IOException -> "The file could not be read: ${message ?: "input/output error"}."
            is OutOfMemoryError -> "The phone ran out of memory while reading this file."
            else -> "The file could not be imported (${this.javaClass.simpleName})."
        }
        return kind
    }
}
