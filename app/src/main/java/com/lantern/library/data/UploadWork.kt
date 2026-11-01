package com.lantern.library.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads that do not need Lore to be on screen.
 *
 * ## What was wrong before
 *
 * An upload was a coroutine launched by the view model. That is fine while the app is in front, and
 * wrong the moment the reader switches away: the ViewModel is scoped to the Activity's store, the
 * coroutine dies with it, and a half-sent book was left with a journal entry that said "interrupted"
 * and nothing left to continue it. Nothing outside the UI could restart the work.
 *
 * ## What replaced it
 *
 * Each book that still owes the account a file becomes one WorkManager job:
 *
 * - **Durable.** WorkManager's database is on disk, so the job survives the Activity, the process
 *   and a device restart, and the system starts it again when the conditions are met.
 * - **Constrained.** `NetworkType.CONNECTED` plus exponential backoff, so "retry when connectivity
 *   returns" is the platform's job rather than a polling loop.
 * - **Headless.** [UploadWorker] builds everything it needs from its own input data: the namespace
 *   that owns the book, that namespace's account id and the book id. It never touches
 *   `LanternStore`, a composition, or an Activity, so an upload can run in a process that has never
 *   drawn a frame.
 * - **Owned.** The worker refuses to run when the current Google identity is not the job's account,
 *   and the namespace must agree. A job created for Account A can therefore never write into
 *   Account B's library, even if both accounts are on the same device.
 *
 * ## Concurrency
 *
 * The three-slot cap is not re-implemented here. Every upload — foreground pass or worker — goes
 * through [UploadPool], which owns the cap and the single-flight set. WorkManager may happily start
 * more workers than that; the pool is what makes the fourth book wait.
 *
 * ## Not resumable, and not pretending to be
 *
 * Drive's simple upload endpoints (`uploadType=multipart` create, `uploadType=media` replace) are
 * used, and neither can resume from a byte offset. An interrupted transfer is therefore **retried
 * from the beginning**, and [DriveLibrary.upload] looks the book up by its `loreId` before sending,
 * replacing that file's media instead of creating a second copy. That lookup is what makes a retry
 * idempotent, and it is the honest description of what happens.
 */

/** One book that still owes the account its bytes. Exactly the record `upload_queue.json` holds. */
data class PendingTransfer(
    val bookId: String,
    val title: String,
    val attempts: Int,
    val lastTryAt: Long,
    val reason: String,
    val retryable: Boolean
)

/**
 * The pending-upload queue, read and written by both the app and the background worker.
 *
 * It lives beside the library it describes, inside the profile directory, so an account's queue
 * cannot be read by walking another account's files. The JSON keys are the ones older versions
 * wrote.
 */
object UploadQueue {

    fun decode(raw: String?): List<PendingTransfer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<PendingTransfer>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                out += PendingTransfer(
                    bookId = id,
                    title = row.optString("title"),
                    attempts = row.optInt("attempts", 0),
                    lastTryAt = row.optLong("at", 0L),
                    reason = row.optString("reason"),
                    retryable = row.optBoolean("retryable", true)
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun encode(list: List<PendingTransfer>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.bookId)
                    .put("title", t.title)
                    .put("attempts", t.attempts)
                    .put("at", t.lastTryAt)
                    .put("reason", t.reason)
                    .put("retryable", t.retryable)
            )
        }
        return arr.toString()
    }

    fun read(file: File): List<PendingTransfer> =
        decode(runCatching { if (file.exists()) file.readText() else null }.getOrNull())

    fun write(file: File, list: List<PendingTransfer>) {
        if (list.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.writeText(encode(list)) }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true) }
            runCatching { tmp.delete() }
        }
    }

    /** Adds or replaces one entry, keeping the others. The attempt count is carried forward. */
    fun note(file: File, entry: PendingTransfer) {
        synchronized(UploadQueue) {
            val list = read(file).toMutableList()
            val previous = list.firstOrNull { it.bookId == entry.bookId }
            list.removeAll { it.bookId == entry.bookId }
            // Attempts only ever grow: a retry that reports "0" is not a new sequence, it is the
            // same book still failing.
            list += entry.copy(attempts = maxOf(entry.attempts, (previous?.attempts ?: 0) + 1))
            write(file, list)
        }
    }

    fun remove(file: File, bookId: String) {
        synchronized(UploadQueue) {
            val list = read(file)
            if (list.none { it.bookId == bookId }) return
            write(file, list.filterNot { it.bookId == bookId })
        }
    }
}

/**
 * The rules that decide whether a background job may touch a book.
 *
 * Pure, so the answer can be tested on the JVM rather than discovered on a phone. Every input is a
 * fact the worker can establish for itself before doing anything.
 */
object UploadOwnership {

    sealed class Decision {
        /** The job is this account's, the namespace is this account's, and the identity matches. */
        object Run : Decision()

        /**
         * The device's signed-in identity is not the job's account — signed out, or a different
         * account. The job must not run, must not be cancelled (its book is still owed), and must
         * be left for the account that owns it.
         */
        data class Detached(val jobAccountId: String, val currentAccountId: String?) : Decision()

        /** The namespace does not belong to the job's account. Never run; never write to it. */
        data class Refused(val reason: String) : Decision()
    }

    /**
     * @param jobNamespace the namespace recorded when the job was created.
     * @param jobAccountId the stable Google account id recorded when the job was created.
     * @param currentAccountId the device's currently signed-in Google account id, if any.
     * @param profileOwner the account id written in that namespace's `profile.json`.
     */
    fun decide(
        jobNamespace: String,
        jobAccountId: String,
        currentAccountId: String?,
        profileOwner: String?
    ): Decision {
        if (!Profiles.isValidNamespace(jobNamespace)) {
            return Decision.Refused("that job's namespace is not one this app can have created")
        }
        if (jobNamespace == Profiles.GUEST) {
            // A guest has no Drive. Guest bytes stay on the phone; there is nothing to upload.
            return Decision.Refused("guest books are local to this phone and are not uploaded")
        }
        if (jobAccountId.isBlank()) return Decision.Refused("that job does not say which account owns it")
        if (profileOwner != jobAccountId) {
            return Decision.Refused(
                "the library in ${jobNamespace} belongs to another account, so this job was refused"
            )
        }
        if (currentAccountId != jobAccountId) return Decision.Detached(jobAccountId, currentAccountId)
        return Decision.Run
    }
}

/** The identity of one upload job: what it is called and what it carries. */
object UploadJobs {

    const val KEY_NAMESPACE = "namespace"
    const val KEY_ACCOUNT = "accountId"
    const val KEY_BOOK = "bookId"

    const val TAG_ALL = "lore-upload"
    private const val TAG_PREFIX_ACCOUNT = "account:"
    private const val TAG_PREFIX_NAMESPACE = "namespace:"

    fun accountTag(accountId: String): String = TAG_PREFIX_ACCOUNT + accountId
    fun namespaceTag(namespace: String): String = TAG_PREFIX_NAMESPACE + namespace

    /**
     * One job per book per account.
     *
     * Unique by name so that pressing "back up now" twice, or the launch-time sweep finding the same
     * book, cannot queue two uploads of it. `KEEP` means an in-flight job is never replaced by a
     * duplicate; the existing one is doing the same work.
     */
    fun name(namespace: String, bookId: String): String = "upload:$namespace:$bookId"

    fun data(namespace: String, accountId: String, bookId: String): Data = Data.Builder()
        .putString(KEY_NAMESPACE, namespace)
        .putString(KEY_ACCOUNT, accountId)
        .putString(KEY_BOOK, bookId)
        .build()
}

/** Queues and cancels the durable uploads. The only place that talks to WorkManager. */
object UploadScheduler {

    /**
     * Queues one book for background upload.
     *
     * Guest books are not queued at all: a Drive upload needs an account, and a guest's books are
     * theirs to keep on this phone.
     */
    fun enqueue(context: Context, namespace: String, accountId: String?, bookId: String) {
        if (namespace == Profiles.GUEST) return
        val account = accountId?.trim().orEmpty()
        if (account.isEmpty()) return
        if (!Profiles.isValidNamespace(namespace)) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(UploadJobs.data(namespace, account, bookId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // A book that failed on a bad connection should not hammer the account: exponential
            // backoff from thirty seconds, which is WorkManager's floor, capped by the system.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UploadJobs.TAG_ALL)
            .addTag(UploadJobs.accountTag(account))
            .addTag(UploadJobs.namespaceTag(namespace))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UploadJobs.name(namespace, bookId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    /** Queues every book that still owes [namespace] its bytes. */
    fun enqueueAll(context: Context, namespace: String, accountId: String?, bookIds: Collection<String>) {
        bookIds.forEach { enqueue(context, namespace, accountId, it) }
    }

    /**
     * Stops every job belonging to [accountId].
     *
     * Called on sign-out and on an account switch. The jobs are *cancelled*, not forgotten: the
     * queue file in that account's namespace still lists the books, so signing back in re-queues
     * them from the same list. Nothing is reassigned to whoever signs in next.
     */
    fun cancelAccount(context: Context, accountId: String?) {
        val account = accountId?.trim().orEmpty()
        if (account.isEmpty()) return
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(UploadJobs.accountTag(account))
        }
    }

    /** Stops every upload, whatever account it belongs to. Used when the app forgets its session. */
    fun cancelAll(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(UploadJobs.TAG_ALL) }
    }
}

/**
 * Uploads one book, with no UI anywhere in the picture.
 *
 * The worker's whole job is: prove it is allowed to act, get a token without asking an Activity,
 * then hand the book to the same [UploadPool] the foreground path uses and write down what happened.
 * Everything it writes goes into the namespace named by its input data.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val namespace = inputData.getString(UploadJobs.KEY_NAMESPACE).orEmpty()
        val accountId = inputData.getString(UploadJobs.KEY_ACCOUNT).orEmpty()
        val bookId = inputData.getString(UploadJobs.KEY_BOOK).orEmpty()
        if (bookId.isBlank()) return@withContext Result.success()

        val profiles = ProfileStore(applicationContext as android.app.Application)
        val currentAccount = runCatching { GoogleAuth.accountId(applicationContext) }.getOrNull()
        val owner = profiles.identity(namespace)?.ownerAccountId

        when (val decision = UploadOwnership.decide(namespace, accountId, currentAccount, owner)) {
            is UploadOwnership.Decision.Refused -> {
                SyncDiag.fail("Background upload", decision.reason)
                return@withContext Result.success()
            }
            is UploadOwnership.Decision.Detached -> {
                // Not this identity's work. Leave the queue entry untouched — it is still owed, by
                // an account that is not signed in — and say so, rather than failing the book.
                SyncDiag.mark(
                    "Background upload",
                    "held back: the job belongs to ${decision.jobAccountId.take(6)}…, " +
                        "this device is signed in as ${decision.currentAccountId?.take(6) ?: "nobody"}"
                )
                return@withContext Result.success()
            }
            UploadOwnership.Decision.Run -> Unit
        }

        val queueFile = profiles.file(namespace, "upload_queue.json")
        val libraryFile = profiles.file(namespace, "library.json")
        val journalFile = profiles.file(namespace, "upload_journal.json")
        val prefs = profiles.preferences(namespace)

        // The reader's own pause wins over any scheduler: a paused book must not start because a
        // worker happened to wake up.
        val paused = prefs.getStringSet("paused_uploads", emptySet()).orEmpty()
        if (bookId in paused) return@withContext Result.success()

        val book = BookRecords.read(libraryFile).firstOrNull { it.id == bookId }
            ?: return@withContext Result.success() // deleted or restored elsewhere: nothing owed
        val file = book.filePath?.let(::File)
        if (file == null || !file.exists() || file.length() <= 0L) {
            UploadQueue.note(
                queueFile,
                PendingTransfer(
                    bookId, book.title, 0, System.currentTimeMillis(),
                    "the local file is not on this phone any more", retryable = false
                )
            )
            return@withContext Result.success()
        }

        val token = when (val result = GoogleAuth.driveToken(applicationContext)) {
            is DriveTokenResult.Ok -> result.token
            is DriveTokenResult.Recoverable -> {
                // Drive consent needs a screen; a worker cannot show one. Record the state so the
                // app can explain it on the next open, and leave the book queued.
                UploadQueue.note(
                    queueFile,
                    PendingTransfer(
                        bookId, book.title, 0, System.currentTimeMillis(),
                        "Drive access needs your approval — open Lore to grant it", retryable = true
                    )
                )
                SyncDiag.fail("Background upload", "paused: Drive access needs the reader's approval")
                return@withContext Result.success()
            }
            DriveTokenResult.Unavailable -> {
                UploadQueue.note(
                    queueFile,
                    PendingTransfer(
                        bookId, book.title, 0, System.currentTimeMillis(),
                        "no Drive access on this device yet — it will be retried", retryable = true
                    )
                )
                return@withContext Result.retry()
            }
        }

        val listener = PersistentUploadListener(namespace, journalFile, libraryFile, queueFile, profiles)
        val result = UploadPool.shared.run(token, listOf(book), listener)
        val confirmed = result.confirmed[book.id]
        val failure = result.failed.firstOrNull { it.first.id == book.id }?.second
        when {
            confirmed != null -> {
                result.unauthorized // deliberate no-op: the pool already recorded the reason
                Result.success()
            }
            result.unauthorized -> Result.retry()
            failure != null && failure.retryable -> Result.retry()
            failure != null -> Result.success()
            // Skipped: another transfer of this book is running in this process. Not a failure, and
            // the owning transfer will write the result.
            else -> Result.success()
        }
    }

    /**
     * Writes each step of a background transfer where the app will find it: the live bus for a
     * running process, and the namespace's journal for one that is about to die.
     */
    private class PersistentUploadListener(
        private val namespace: String,
        private val journalFile: File,
        private val libraryFile: File,
        private val queueFile: File,
        private val profiles: ProfileStore
    ) : UploadListener {

        override fun onStarted(book: LibraryBook, totalBytes: Long) {
            TransferBus.start(book.id, totalBytes)
            journal(book.id, 0L, totalBytes)
        }

        override fun onProgress(book: LibraryBook, sent: Long, total: Long) {
            TransferBus.sent(book.id, sent, total)
            journal(book.id, sent, total)
        }

        override fun onVerifying(book: LibraryBook, totalBytes: Long) {
            TransferBus.verifying(book.id, totalBytes)
            journal(book.id, totalBytes, totalBytes)
        }

        override fun onVerified(book: LibraryBook, driveFileId: String, bytes: Long) {
            TransferBus.clear(book.id)
            val now = System.currentTimeMillis()
            BookRecords.update(libraryFile, book.id) { latest ->
                latest.copy(
                    driveFileId = driveFileId,
                    pendingUpload = false,
                    backupFailed = false,
                    backupAt = now,
                    backupBytes = bytes
                )
            }
            // The books this phone has confirmed are exactly the ones whose journal entry may go.
            clearJournalEntry(book.id)
            UploadQueue.remove(queueFile, book.id)
            SyncDiag.mark("Background upload", "verified — ${book.title}")
        }

        override fun onFailed(book: LibraryBook, failure: DriveLibrary.TransferFailure, reachedBytes: Long) {
            TransferBus.failed(book.id, reachedBytes, TransferBus.progressFor(book.id)?.totalBytes ?: 0L, failure.reason)
            clearJournalEntry(book.id)
            UploadQueue.note(
                queueFile,
                PendingTransfer(
                    book.id, book.title, 1, System.currentTimeMillis(), failure.reason, failure.retryable
                )
            )
            SyncDiag.fail("Background upload", "${book.title}: ${failure.reason}")
        }

        override fun onPaused(book: LibraryBook, sent: Long, total: Long, reason: String) {
            TransferBus.paused(book.id, sent, total, reason)
            journal(book.id, sent, total)
        }

        override fun onSkipped(book: LibraryBook, reason: String) {
            SyncDiag.mark("Background upload", "${book.title}: $reason")
        }

        private var lastFlush = 0L

        /** Throttled to two seconds per book, exactly like the foreground journal. */
        private fun journal(bookId: String, sent: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastFlush < 2_000L) return
            lastFlush = now
            synchronized(UploadQueue) {
                val list = TransferJournal.decode(
                    runCatching { if (journalFile.exists()) journalFile.readText() else null }.getOrNull()
                ).filterNot { it.bookId == bookId } + TransferProgress.paused(bookId, sent, total)
                runCatching { journalFile.writeText(TransferJournal.encode(list.take(200))) }
            }
        }

        private fun clearJournalEntry(bookId: String) {
            synchronized(UploadQueue) {
                val list = TransferJournal.decode(
                    runCatching { if (journalFile.exists()) journalFile.readText() else null }.getOrNull()
                ).filterNot { it.bookId == bookId }
                if (list.isEmpty()) runCatching { journalFile.delete() }
                else runCatching { journalFile.writeText(TransferJournal.encode(list)) }
            }
        }
    }

    companion object {
        /** Namespace helper kept here so the worker and the app cannot disagree on the layout. */
        fun libraryFile(profiles: ProfileStore, namespace: String): File =
            profiles.file(namespace, "library.json")
    }
}
