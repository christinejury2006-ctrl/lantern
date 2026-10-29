package com.lantern.library.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the pool tells the caller while it works. Every callback can arrive on any thread. */
interface UploadListener {
    fun onStarted(book: LibraryBook, totalBytes: Long) {}
    fun onProgress(book: LibraryBook, sent: Long, total: Long) {}
    /** This book's bytes are out; its own verification is starting now. */
    fun onVerifying(book: LibraryBook, totalBytes: Long) {}
    fun onVerified(book: LibraryBook, driveFileId: String, bytes: Long) {}
    fun onFailed(book: LibraryBook, failure: DriveLibrary.TransferFailure, reachedBytes: Long) {}
    /** Cancelled mid-transfer — not a failure of the book, an interruption. */
    fun onPaused(book: LibraryBook, sent: Long, total: Long, reason: String) {}
    /** The book was already being uploaded by another caller; this pass left it alone. */
    fun onSkipped(book: LibraryBook, reason: String) {}
    /** One attempt failed and will be repeated: transient, so the card keeps its percentage. */
    fun onRetry(book: LibraryBook, attempt: Int, reason: String) {}
}

/**
 * Bounded, per-book upload and verification.
 *
 * ## Why this exists
 *
 * Uploading was a `for` loop: book A was sent, checked, and only then did book B start. Two books
 * therefore took twice as long as one, a single slow file stalled the whole shelf, and a finished
 * file's verification was tied to the loop's position rather than to that file.
 *
 * This class turns the loop into independent per-book jobs:
 *
 * 1. **Bounded concurrency.** At most [maxConcurrentUploads] files are in flight, and a separate,
 *    equally bounded set of verifications runs alongside them. The limits are shared by the whole
 *    process (`UploadPool.shared`), so a sync pass, a queue retry and a manual retry cannot add up
 *    to eight parallel transfers on a phone connection.
 * 2. **Verification is per book and immediate.** The moment one book's last byte is out, that book
 *    moves to `VERIFYING` and its checksum/read-back runs — while other books are still
 *    transferring. Verification takes its own permit, so it never queues behind an unrelated
 *    upload and never blocks one.
 * 3. **One job per book.** [inFlight] is a process-wide set keyed by book id: a second caller that
 *    asks for the same book while it is being sent is told the book is `onSkipped` instead of
 *    starting a duplicate transfer. Together with DriveLibrary's `loreId` lookup this is what
 *    keeps a retry from ever producing a second Drive file.
 * 4. **Isolation.** A failure, an exception, or a cancellation in one book ends that book only:
 *    the jobs run in a `supervisorScope`, and every book's body is wrapped so nothing it throws can
 *    cancel a sibling.
 *
 * Everything here is plain Kotlin — no Android, no Compose — so the concurrency rules can be tested
 * deterministically against a fake transport.
 */
class UploadPool(
    val maxConcurrentUploads: Int = MAX_CONCURRENT_UPLOADS,
    val maxConcurrentVerifications: Int = MAX_CONCURRENT_VERIFICATIONS
) {
    companion object {
        /**
         * Three at a time.
         *
         * Enough that a multi-book sync is not a queue of one, small enough that three
         * simultaneous uploads on a mobile connection do not starve each other, and far below
         * Drive's and OkHttp's limits.
         */
        const val MAX_CONCURRENT_UPLOADS = 3
        const val MAX_CONCURRENT_VERIFICATIONS = 3

        /**
         * The one pool the app uses.
         *
         * A single instance is the point: the per-book single-flight set and the semaphores must
         * be shared by every entry point, or "at most three uploads" would be "at most three per
         * caller".
         */
        val shared = UploadPool()
    }

    private val uploadSlots = Semaphore(maxConcurrentUploads)
    private val verifySlots = Semaphore(maxConcurrentVerifications)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** For tests and diagnostics: is a transfer for this book running right now? */
    fun isInFlight(bookId: String): Boolean = bookId in inFlight

    /** What one run of the pool achieved. */
    data class Result(
        /** book id -> (Drive file id, bytes verified). Only books that finished verification. */
        val confirmed: Map<String, Pair<String, Long>>,
        /** Books that did not make it, with the reason, in order of completion. */
        val failed: List<Pair<LibraryBook, DriveLibrary.TransferFailure>>,
        /** Books another caller was already uploading. Not failures; not done either. */
        val skipped: List<LibraryBook>,
        /** The access token was refused: the caller should re-authorise and try again. */
        val unauthorized: Boolean
    )

    /**
     * Uploads and verifies [books] concurrently, and returns once every one of them has settled.
     *
     * @param token a Drive access token. A refused token ends its book with
     *   [DriveLibrary.TransferFailure] and sets [Result.unauthorized]; it does not cancel the other
     *   books, because a token can be refused for one request and accepted for the next.
     */
    suspend fun run(
        token: String,
        books: List<LibraryBook>,
        listener: UploadListener = object : UploadListener {}
    ): Result = supervisorScope {
        val confirmed = ConcurrentHashMap<String, Pair<String, Long>>()
        val failed = ConcurrentLinkedQueue<Pair<LibraryBook, DriveLibrary.TransferFailure>>()
        val skipped = ConcurrentLinkedQueue<LibraryBook>()
        val unauthorized = AtomicBoolean(false)

        // distinctBy: the same book twice in one list is a caller bug, not two transfers.
        //
        // The jobs are collected and joined explicitly. `launch` is not `await`, and the scope only
        // waits for its children once this block has returned — so building the result here
        // without joining would return an empty answer for a pass that is still running. That is
        // not theoretical: it made a batch report zero confirmations while the listener had
        // already verified every book, which is exactly the "0 confirmed, 2 failed" kind of lie
        // this whole path exists to stop.
        val jobs = ArrayList<Job>(books.size)
        books.distinctBy { it.id }.forEach { book ->
            if (!inFlight.add(book.id)) {
                skipped += book
                listener.onSkipped(book, "already uploading")
                return@forEach
            }
            jobs += launch(Dispatchers.IO) {
                try {
                    uploadOne(token, book, listener, confirmed, failed, unauthorized)
                } catch (cancelled: CancellationException) {
                    // The app went away, or the caller cancelled: the book is not broken, it is
                    // interrupted. Say so and let the cancellation propagate.
                    val progress = TransferBus.progressFor(book.id)
                    listener.onPaused(
                        book,
                        progress?.sentBytes ?: 0L,
                        progress?.totalBytes ?: 0L,
                        "interrupted"
                    )
                    throw cancelled
                } catch (t: Throwable) {
                    // A bug in one book must not cost the others their upload.
                    val failure = DriveLibrary.TransferFailure(
                        book.id,
                        book.title,
                        "upload",
                        "${t.javaClass.simpleName}: " + t.message.orEmpty().take(120),
                        retryable = true
                    )
                    failed += book to failure
                    listener.onFailed(book, failure, TransferBus.progressFor(book.id)?.sentBytes ?: 0L)
                } finally {
                    inFlight.remove(book.id)
                }
            }
        }
        // Wait for every book. Only then does the result describe the pass that actually happened.
        jobs.joinAll()

        Result(
            confirmed = confirmed.toMap(),
            failed = failed.toList(),
            skipped = skipped.toList(),
            unauthorized = unauthorized.get()
        )
    }

    /** One book, end to end: transfer inside an upload permit, then verify inside a verify permit. */
    private suspend fun uploadOne(
        token: String,
        book: LibraryBook,
        listener: UploadListener,
        confirmed: ConcurrentHashMap<String, Pair<String, Long>>,
        failed: ConcurrentLinkedQueue<Pair<LibraryBook, DriveLibrary.TransferFailure>>,
        unauthorized: AtomicBoolean
    ) {
        val file = book.filePath?.let(::File)
        if (file == null || !file.exists() || file.length() <= 0L) {
            // Not a network problem: there is nothing to send. Reported as a restore/re-import.
            val failure = DriveLibrary.TransferFailure(
                book.id, book.title, "upload", "no readable local file to send", retryable = false
            )
            failed += book to failure
            listener.onFailed(book, failure, 0L)
            return
        }
        val total = file.length()
        listener.onStarted(book, total)

        // --- transfer, holding an upload permit only for as long as bytes are moving ----------
        val alreadyThere = AtomicBoolean(false)
        val sent = uploadSlots.withPermit {
            val existing = book.driveFileId
            if (!existing.isNullOrBlank() && !BackupRules.changedSinceBackup(book, true) &&
                DriveLibrary.remoteMatches(token, existing, file) == true
            ) {
                // The account already holds these exact bytes: the confirmation is the missing
                // piece, and re-sending the file would be wasted traffic, not extra safety.
                alreadyThere.set(true)
                DriveLibrary.Sent.Ok(existing)
            } else {
                DriveLibrary.transfer(
                    token, book, file,
                    onAttempt = { attempt, reason -> listener.onRetry(book, attempt, reason) },
                    onProgress = { sentBytes, size -> listener.onProgress(book, sentBytes, size) }
                )
            }
        }

        when (sent) {
            is DriveLibrary.Sent.Ok -> {
                if (!alreadyThere.get()) {
                    // The upload permit is released at this point: this book's verification can
                    // start now, while other books keep transferring.
                    listener.onVerifying(book, total)
                }
                val verified = verifySlots.withPermit {
                    if (alreadyThere.get()) {
                        DriveLibrary.Verified.Ok(sent.fileId)
                    } else {
                        // No byte reporting here on purpose. The rare repair re-send inside
                        // verification moves bytes, but this book's phase is VERIFYING and its
                        // card must not drop back to "uploading 60%" — that is exactly the kind of
                        // jump the reader would read as a restart.
                        DriveLibrary.verifyUploaded(token, book, sent.fileId, file)
                    }
                }
                when (verified) {
                    is DriveLibrary.Verified.Ok -> {
                        confirmed[book.id] = verified.fileId to total
                        listener.onVerified(book, verified.fileId, total)
                    }
                    DriveLibrary.Verified.Unauthorized -> {
                        unauthorized.set(true)
                        val failure = DriveLibrary.TransferFailure(
                            book.id, book.title, "upload",
                            "Google rejected the sign-in — sign in again", retryable = false
                        )
                        failed += book to failure
                        listener.onFailed(book, failure, total)
                    }
                    is DriveLibrary.Verified.Failed -> {
                        val failure = DriveLibrary.TransferFailure(
                            book.id, book.title, "verify", verified.reason, verified.retryable
                        )
                        failed += book to failure
                        listener.onFailed(book, failure, total)
                    }
                }
            }
            DriveLibrary.Sent.Unauthorized -> {
                unauthorized.set(true)
                val failure = DriveLibrary.TransferFailure(
                    book.id, book.title, "upload",
                    "Google rejected the sign-in — sign in again", retryable = false
                )
                failed += book to failure
                listener.onFailed(book, failure, TransferBus.progressFor(book.id)?.sentBytes ?: 0L)
            }
            is DriveLibrary.Sent.Failed -> {
                val classified = RetryPolicy.classify(sent.code)
                if (classified == TransferClass.UNAUTHORIZED) unauthorized.set(true)
                val failure = DriveLibrary.TransferFailure(
                    book.id,
                    book.title,
                    "upload",
                    RetryPolicy.explain(classified, sent.code, sent.detail),
                    retryable = classified == TransferClass.TRANSIENT || sent.retryable
                )
                failed += book to failure
                listener.onFailed(book, failure, TransferBus.progressFor(book.id)?.sentBytes ?: 0L)
            }
        }
    }
}
