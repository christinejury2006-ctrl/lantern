package com.lantern.library.data

/**
 * The four numbers on the cloud-library panel, and the arithmetic behind them.
 *
 * A panel that says "8 on the shelf, 5 verified, 1 failed, 1 uploading" invites the reader to add
 * the numbers up, and if they do not add up the whole screen stops being believable. So every
 * book on the shelf lands in **exactly one** bucket here, and [Metrics.accountedFor] is asserted
 * against [Metrics.total] by the tests.
 *
 * The buckets are:
 *
 * | bucket             | meaning                                                        |
 * |--------------------|----------------------------------------------------------------|
 * | [verified]         | a confirmed cloud copy *and* the file on this phone            |
 * | [uploading]        | bytes moving, or being verified, right now                     |
 * | [paused]           | an upload the reader paused: **not** transmitting              |
 * | [failed]           | the last attempt failed and needs attention                    |
 * | [queued]           | waiting its turn, or waiting for a connection                  |
 * | [restoreRequired]  | the account has it, this phone does not                        |
 * | [localOnly]        | no copy anywhere yet                                           |
 * | [notApplicable]    | a bundled or plain-text book: there is no file to back up      |
 *
 * Three rules that used to be wrong somewhere in the app and are now one function:
 *
 * 1. **A queued book is never "uploading".** Only a live transfer speaks for that.
 * 2. **A paused book is never "uploading" either.** It is reported on its own, because "3
 *    uploading" over a phone that is sending nothing is a lie with a spinner on it.
 * 3. **100% is not verified.** Only a stored confirmation counts; a book mid-verification is in
 *    [uploading] until the checksum comes back.
 */
object CloudPanel {

    /** One bucket per book, plus the total they must add up to. */
    data class Metrics(
        val total: Int,
        val verified: Int,
        val uploading: Int,
        val paused: Int,
        val failed: Int,
        val queued: Int,
        val restoreRequired: Int,
        val localOnly: Int,
        val notApplicable: Int
    ) {
        /** Every bucket, summed. Must equal [total]; the tests say so. */
        val accountedFor: Int
            get() = verified + uploading + paused + failed + queued + restoreRequired + localOnly + notApplicable

        /** True when the buckets and the shelf agree — the panel's own arithmetic. */
        val consistent: Boolean get() = accountedFor == total

        /** Everything the account can actually hold, i.e. the shelf minus text and bundled books. */
        val cloudBooks: Int get() = total - notApplicable

        /**
         * The books that are neither done nor actively moving: the line under the four big
         * numbers. Genuinely separate states, named separately, rather than folded into "failed".
         */
        fun remainder(): List<Pair<String, Int>> = buildList {
            if (queued > 0) add("Waiting to upload" to queued)
            if (paused > 0) add("Paused" to paused)
            if (restoreRequired > 0) add("On the account, not on this phone" to restoreRequired)
            if (localOnly > 0) add("Not backed up yet" to localOnly)
            if (notApplicable > 0) add("No file to back up" to notApplicable)
        }
    }

    /**
     * Counts the shelf.
     *
     * @param transfers live progress by book id — the only source allowed to say "uploading".
     * @param queuedIds books the retry queue is holding that are not in flight.
     * @param pausedUploads books the reader paused, whether or not a job still exists for them.
     * @param online whether there is a connection, which turns a failure into "waiting".
     */
    fun metrics(
        books: List<LibraryBook>,
        transfers: Map<String, TransferProgress> = emptyMap(),
        queuedIds: Set<String> = emptySet(),
        pausedUploads: Set<String> = emptySet(),
        online: Boolean = true
    ): Metrics {
        var verified = 0
        var uploading = 0
        var paused = 0
        var failed = 0
        var queued = 0
        var restore = 0
        var localOnly = 0
        var notApplicable = 0

        books.forEach { book ->
            when (bucketOf(book, transfers[book.id], book.id in queuedIds, book.id in pausedUploads, online)) {
                Bucket.VERIFIED -> verified++
                Bucket.UPLOADING -> uploading++
                Bucket.PAUSED -> paused++
                Bucket.FAILED -> failed++
                Bucket.QUEUED -> queued++
                Bucket.RESTORE_REQUIRED -> restore++
                Bucket.LOCAL_ONLY -> localOnly++
                Bucket.NOT_APPLICABLE -> notApplicable++
            }
        }
        return Metrics(
            total = books.size,
            verified = verified,
            uploading = uploading,
            paused = paused,
            failed = failed,
            queued = queued,
            restoreRequired = restore,
            localOnly = localOnly,
            notApplicable = notApplicable
        )
    }

    enum class Bucket { VERIFIED, UPLOADING, PAUSED, FAILED, QUEUED, RESTORE_REQUIRED, LOCAL_ONLY, NOT_APPLICABLE }

    /** Which single bucket one book belongs to. Precedence is the whole point of this function. */
    fun bucketOf(
        book: LibraryBook,
        transfer: TransferProgress?,
        queued: Boolean,
        pausedByReader: Boolean,
        online: Boolean
    ): Bucket {
        // A bundled or plain-text book has no file to store, so it is neither done nor owed.
        if (book.origin == BookOrigin.BUNDLED || book.format == BookFormat.TEXT) return Bucket.NOT_APPLICABLE

        // 1. A live transfer speaks first. A restore is not an upload: the account already holds
        //    the file, so the book stays in "on the account, not on this phone" while it comes back.
        if (transfer != null && !transfer.restore) {
            when (transfer.phase) {
                TransferPhase.PREPARING, TransferPhase.UPLOADING, TransferPhase.VERIFYING -> return Bucket.UPLOADING
                TransferPhase.PAUSED -> return Bucket.PAUSED
                TransferPhase.FAILED ->
                    return if (online) Bucket.FAILED else Bucket.QUEUED
            }
        }

        // 2. A restore in flight (or paused) is still a book the phone does not have.
        if (transfer != null && transfer.restore) return Bucket.RESTORE_REQUIRED

        // 3. What the persisted record says, corrected for a file that changed after it was
        //    verified (BackupAudit.effective is the one place that rule lives).
        return when (BackupAudit.effective(book)) {
            BookBackupState.BACKED_UP ->
                if (book.localFileAvailable) Bucket.VERIFIED else Bucket.RESTORE_REQUIRED
            BookBackupState.RESTORE_REQUIRED -> Bucket.RESTORE_REQUIRED
            BookBackupState.FAILED -> when {
                pausedByReader -> Bucket.PAUSED
                !online || queued -> Bucket.QUEUED
                else -> Bucket.FAILED
            }
            BookBackupState.NOT_APPLICABLE -> Bucket.NOT_APPLICABLE
            // Nothing proven yet. A paused upload stays paused; a book with an id is waiting its
            // turn; a book with no id at all has never been anywhere.
            BookBackupState.PENDING -> when {
                pausedByReader -> Bucket.PAUSED
                // The journal still owes these bytes, so "waiting" is a fact about the queue, not
                // an inference from an id that may or may not exist.
                queued -> Bucket.QUEUED
                !book.driveFileId.isNullOrBlank() -> Bucket.QUEUED
                else -> Bucket.LOCAL_ONLY
            }
        }
    }
}
