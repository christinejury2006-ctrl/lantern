package com.lantern.library.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Applies a change to the map inside its own snapshot.
 *
 * Progress arrives on the OkHttp writer thread, which is not a composition; writing Compose state
 * without a snapshot from there is the kind of thing that works in a demo and throws on a
 * release build. `withMutableSnapshot` is the supported way to publish a value from any thread.
 */
private inline fun edit(block: () -> Unit) {
    Snapshot.withMutableSnapshot(block)
}

/**
 * Live byte progress for every book that is transferring.
 *
 * This is the one piece of sync state the shelf reads directly, and it exists because the shelf
 * must not be rebuilt to show a percentage: the library list is the source of truth for *which*
 * books exist, and this map is the source of truth for *how far their files have gone*. Two
 * houses, two questions, no reloading.
 *
 * Three rules keep it honest and cheap:
 *
 * 1. **Bytes, not estimates.** Values come from the request body counting what it writes to the
 *    socket, so the percentage is the transfer itself.
 * 2. **Notify on change of whole percent (or phase), not on every 8 KB chunk.** Otherwise a card
 *    would recompose thousands of times for one book and the whole shelf would stutter.
 * 3. **Verified is never a percentage.** A transfer that has sent 100% of its bytes is in
 *    [TransferPhase.VERIFYING] until Drive's checksum (or the read-back compare) agrees; only then
 *    is the entry cleared and the book's own record allowed to say "verified".
 */
object TransferBus {

    private val entries = mutableStateMapOf<String, TransferProgress>()

    /**
     * The live map itself, for the shelf.
     *
     * Deliberately not a copy: the shelf reads `entries[book.id]` inside the tile, so Compose can
     * invalidate just the scopes that read the value, and a copy would also allocate on every
     * frame of a transfer. Read-only in practice — nothing outside this object writes to it.
     */
    val states: Map<String, TransferProgress> get() = entries

    /** Snapshot for non-Compose callers (diagnostics, tests). */
    fun snapshot(): Map<String, TransferProgress> = entries.toMap()

    /** What the card should draw for [bookId], or null when nothing is happening. */
    fun progressFor(bookId: String): TransferProgress? = entries[bookId]

    /** The ids with a live transfer, for the audit counts. */
    fun activeIds(): Set<String> = entries.values
        .filter { it.phase == TransferPhase.PREPARING || it.phase == TransferPhase.UPLOADING || it.phase == TransferPhase.VERIFYING }
        .mapTo(HashSet()) { it.bookId }

    /** Books whose transfer the reader paused, either direction. */
    fun pausedIds(): Set<String> = entries.values
        .filter { it.phase == TransferPhase.PAUSED }
        .mapTo(HashSet()) { it.bookId }

    fun start(bookId: String, totalBytes: Long) = edit {
        entries[bookId] = TransferProgress.preparing(bookId, totalBytes)
    }

    /**
     * Bytes written to the socket.
     *
     * Throttled to whole percentage points: a 20 MB book sends ~2,500 chunks and the reader can
     * only see 100 values, so the other 2,400 updates would be recompositions nobody asked for.
     */
    fun sent(bookId: String, sentBytes: Long, totalBytes: Long) {
        val previous = entries[bookId]
        val startedAt = previous?.startedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val next = TransferProgress.uploading(bookId, sentBytes, totalBytes, startedAt)
        if (previous != null && previous.phase == TransferPhase.UPLOADING && previous.percent == next.percent) {
            return
        }
        edit { entries[bookId] = next }
    }

    /**
     * A restore: the reader asked for a book back, and bytes are arriving.
     *
     * Same map, same throttling, same "no invented numbers" rule — the difference is only that
     * [TransferProgress.sentBytes] counts downloaded bytes and the card says "Downloading".
     * A separate bus for the other direction would be a second transfer system to keep in sync.
     */
    fun restorePreparing(bookId: String, totalBytes: Long) = edit {
        entries[bookId] = TransferProgress.restorePreparing(bookId, totalBytes)
    }

    fun restoring(bookId: String, receivedBytes: Long, totalBytes: Long) {
        val previous = entries[bookId]
        val startedAt = previous?.startedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val next = TransferProgress.restoring(bookId, receivedBytes, totalBytes, startedAt)
        if (previous != null && previous.phase == TransferPhase.UPLOADING && previous.restore &&
            previous.percent == next.percent
        ) {
            return
        }
        edit { entries[bookId] = next }
    }

    fun restoreVerifying(bookId: String, totalBytes: Long) = edit {
        val previous = entries[bookId]
        val startedAt = previous?.startedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        entries[bookId] = TransferProgress.restoreVerifying(bookId, totalBytes, startedAt)
    }

    /** Every byte is out; the account is being asked to prove it. */
    fun verifying(bookId: String, totalBytes: Long) = edit {
        val previous = entries[bookId]
        val startedAt = previous?.startedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        entries[bookId] = TransferProgress.verifying(bookId, totalBytes, startedAt)
    }

    fun paused(bookId: String, sentBytes: Long, totalBytes: Long, reason: String = "", restore: Boolean = false) = edit {
        entries[bookId] = TransferProgress.paused(bookId, sentBytes, totalBytes, reason, restore)
    }

    fun failed(bookId: String, sentBytes: Long, totalBytes: Long, reason: String) = edit {
        entries[bookId] = TransferProgress.failed(bookId, sentBytes, totalBytes, reason)
    }

    /** The book is done — verified, or deliberately stopped. Nothing to draw any more. */
    fun clear(bookId: String) = edit {
        entries.remove(bookId)
    }

    fun clearAll() = edit { entries.clear() }

    /**
     * Seeds the map from the journal at launch.
     *
     * Without this the shelf would show "failed" for an upload that was merely interrupted by the
     * app being closed, which is the difference between a bug report and a phone reboot.
     */
    fun restorePaused(journal: List<TransferProgress>) = edit {
        journal.forEach { entry ->
            if (entries[entry.bookId] == null) {
                entries[entry.bookId] = entry.copy(phase = TransferPhase.PAUSED)
            }
        }
    }
}
