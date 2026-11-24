package com.lantern.library.data

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import com.lantern.library.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID

/** One monitor shared by the UI and worker while reading or updating restore checkpoints. */
internal object RestoreJournalLock {
    val lock = Any()
}

/** A work UUID survives WorkManager retries but changes for a later restore of the same book. */
internal fun restoreNotificationEventId(
    kind: String,
    namespace: String,
    bookId: String,
    workId: String,
    attempt: Int? = null
): String = buildString {
    append("restore-").append(kind).append(':').append(namespace).append(':').append(bookId)
        .append(':').append(workId)
    if (attempt != null) append(':').append(attempt)
}

/**
 * Durable, account-owned restores. A restore is not a ViewModel coroutine: WorkManager owns its
 * lifetime, and its foreground notification reports the actual transfer stage and byte progress.
 */
object RestoreJobs {
    const val KEY_NAMESPACE = "namespace"
    const val KEY_ACCOUNT = "accountId"
    const val KEY_BOOK = "bookId"
    const val KEY_KEEP_OFFLINE = "keepOffline"
    const val TAG_ALL = "lore-restore"

    fun name(namespace: String, bookId: String) = "restore:$namespace:$bookId"

    fun data(namespace: String, accountId: String, bookId: String, keepOffline: Boolean): Data = Data.Builder()
        .putString(KEY_NAMESPACE, namespace)
        .putString(KEY_ACCOUNT, accountId)
        .putString(KEY_BOOK, bookId)
        .putBoolean(KEY_KEEP_OFFLINE, keepOffline)
        .build()
}

object RestoreScheduler {
    fun enqueue(context: Context, namespace: String, accountId: String, bookId: String, keepOffline: Boolean = true): UUID? {
        if (!Profiles.isValidNamespace(namespace) || namespace == Profiles.GUEST ||
            accountId.isBlank() || bookId.isBlank()
        ) return null
        // A restore is an explicit reader action, so it may use the currently connected network.
        // The backup-only mobile-data preference must not leave an opened book waiting forever.
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(RestoreJobs.data(namespace, accountId, bookId, keepOffline))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(RestoreJobs.TAG_ALL)
            .addTag("restore-account:$accountId")
            .build()
        return runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RestoreJobs.name(namespace, bookId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            request.id
        }.getOrNull()
    }

    /** Waits for the exact restore enqueued by the caller, even if it finishes before observation. */
    suspend fun await(context: Context, namespace: String, bookId: String, workId: UUID? = null): Boolean {
        if (workId == null) return false
        val live = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(RestoreJobs.name(namespace, bookId))
        val finished = CompletableDeferred<Boolean>()
        val observer = Observer<List<WorkInfo>> { infos ->
            // Match the request UUID, not merely the unique-work name. That prevents a previous
            // completed restore from satisfying this wait, and catches a fast job that completed
            // before LiveData delivered an intermediate ENQUEUED/RUNNING state.
            val exact = infos.orEmpty().firstOrNull { it.id == workId } ?: return@Observer
            if (exact.state.isFinished) {
                finished.complete(exact.state == WorkInfo.State.SUCCEEDED)
            }
        }
        try {
            withContext(Dispatchers.Main) { live.observeForever(observer) }
            return withTimeoutOrNull(TimeUnit.MINUTES.toMillis(30)) { finished.await() } ?: false
        } finally {
            withContext(Dispatchers.Main) { live.removeObserver(observer) }
        }
    }

    fun pause(context: Context, namespace: String, bookId: String) {
        val prefs = ProfileStore(context.applicationContext as Application).preferences(namespace)
        val paused = prefs.getStringSet("paused_restores", emptySet()).orEmpty().toMutableSet()
        paused += bookId
        prefs.edit().putStringSet("paused_restores", paused).apply()
    }

    fun resume(context: Context, namespace: String, bookId: String) {
        val prefs = ProfileStore(context.applicationContext as Application).preferences(namespace)
        val paused = prefs.getStringSet("paused_restores", emptySet()).orEmpty().toMutableSet()
        paused -= bookId
        prefs.edit().putStringSet("paused_restores", paused).apply()
    }

    fun cancelAccount(context: Context, accountId: String?) {
        val id = accountId?.trim().orEmpty()
        if (id.isNotEmpty()) runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag("restore-account:$id")
        }
    }
}

class RestoreWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notificationId: Int
        get() = 0x4C000000 or (inputData.getString(RestoreJobs.KEY_BOOK).orEmpty().hashCode() and 0x00FFFFFF)

    /** Stable across WorkManager retries, but different for a later restore of the same book. */
    private val notificationEventScope: String = params.id.toString()

    override suspend fun doWork(): Result {
        val transferBookId = inputData.getString(RestoreJobs.KEY_BOOK).orEmpty()
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            // CoroutineWorker's onStopped is final. Its cancellation still reaches this job;
            // cancel the blocking OkHttp call so the last journalled prefix remains resumable.
            if (cause is CancellationException && transferBookId.isNotBlank()) {
                DriveLibrary.abortTransfer(transferBookId)
            }
        }
        return withContext(Dispatchers.IO) {
        val namespace = inputData.getString(RestoreJobs.KEY_NAMESPACE).orEmpty()
        val accountId = inputData.getString(RestoreJobs.KEY_ACCOUNT).orEmpty()
        val bookId = inputData.getString(RestoreJobs.KEY_BOOK).orEmpty()
        if (!Profiles.isValidNamespace(namespace) || namespace == Profiles.GUEST ||
            accountId.isBlank() || bookId.isBlank()
        ) return@withContext Result.failure()

        val currentAccount = runCatching { GoogleAuth.accountId(applicationContext) }.getOrNull()
        if (currentAccount != accountId || Profiles.namespaceFor(currentAccount) != namespace) {
            SyncDiag.mark("Background restore", "held: the Google account that owns this book is not signed in")
            return@withContext Result.failure()
        }

        val profiles = ProfileStore(applicationContext as Application)
        val identity = profiles.identity(namespace)
        if (identity?.ownerAccountId != accountId) {
            SyncDiag.fail("Background restore", "refused: this library does not belong to the signed-in account")
            return@withContext Result.failure()
        }
        profiles.dir(namespace)
        val libraryFile = profiles.file(namespace, "library.json")
        val book = BookRecords.read(libraryFile).firstOrNull { it.id == bookId }
            ?: return@withContext Result.failure()

        ensureChannel()
        setForeground(foregroundInfo(book.title, "Connecting to Google Drive", 0, book.backupBytes))
        val token = when (val auth = GoogleAuth.driveToken(applicationContext)) {
            is DriveTokenResult.Ok -> auth.token
            is DriveTokenResult.Recoverable -> {
                showNotice(book.title, "Open Lore to reconnect Google Drive before restoring this book.")
                return@withContext Result.failure()
            }
            DriveTokenResult.Unavailable -> {
                NotificationCenter.post(
                    applicationContext, LoreNoticeCategory.DOWNLOADS, "Restore waiting",
                    "Google Drive is temporarily unavailable. Lore will retry this restore.",
                    restoreNotificationEventId("auth-retry", namespace, bookId, notificationEventScope), "library"
                )
                return@withContext Result.retry()
            }
        }
        updateForeground(book.title, "Preparing download", 0, book.backupBytes)
        var lastNoticeAt = 0L
        var lastNoticePercent = -1
        val host = RestoreWorkerHost(applicationContext, profiles, namespace, libraryFile, token) { phase, received, total ->
            val percent = if (total > 0L) ((received.coerceAtLeast(0L) * 100L) / total).toInt() else -1
            val now = System.currentTimeMillis()
            if (phase != "Downloading" || percent != lastNoticePercent && now - lastNoticeAt >= 750L) {
                updateForeground(book.title, phase, received, total)
                lastNoticeAt = now
                lastNoticePercent = percent
            }
        }
        val outcome = DownloadService(host).restoreDownload(bookId, keepOffline = inputData.getBoolean(RestoreJobs.KEY_KEEP_OFFLINE, true))
        when (outcome) {
            is RestoreOutcome.Restored -> {
                TransferBus.clear(bookId)
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                NotificationCenter.post(
                    applicationContext,
                    LoreNoticeCategory.DOWNLOADS,
                    "Book restored",
                    "“${outcome.book.title}” is available offline again.",
                    restoreNotificationEventId("complete", namespace, bookId, notificationEventScope),
                    "reader:$bookId"
                )
                Result.success()
            }
            is RestoreOutcome.AlreadyAvailable -> {
                TransferBus.clear(bookId)
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                Result.success()
            }
            is RestoreOutcome.Paused -> {
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                NotificationCenter.post(
                    applicationContext, LoreNoticeCategory.DOWNLOADS, "Restore paused",
                    "“${book.title}” is paused. Your downloaded bytes are saved.",
                    restoreNotificationEventId("paused", namespace, bookId, notificationEventScope), "library"
                )
                Result.success()
            }
            is RestoreOutcome.Refused -> {
                TransferBus.failed(bookId, TransferBus.progressFor(bookId)?.sentBytes ?: 0L, book.backupBytes, outcome.blocker.message)
                showNotice(book.title, outcome.blocker.message)
                Result.failure()
            }
            is RestoreOutcome.Failed -> {
                val current = TransferBus.progressFor(bookId)
                if (outcome.retryable) {
                    TransferBus.paused(
                        bookId, current?.sentBytes ?: 0L, current?.totalBytes ?: book.backupBytes,
                        "waiting for the connection; Lore will retry", restore = true
                    )
                    NotificationCenter.post(
                        applicationContext, LoreNoticeCategory.DOWNLOADS, "Restore will continue",
                        "“${book.title}” was interrupted. Lore saved its progress and will retry.",
                        restoreNotificationEventId("retry", namespace, bookId, notificationEventScope), "library"
                    )
                    Result.retry()
                } else {
                    TransferBus.failed(bookId, current?.sentBytes ?: 0L, book.backupBytes, outcome.reason)
                    showNotice(book.title, "Could not restore: ${outcome.reason}")
                    Result.failure()
                }
            }
            RestoreOutcome.Busy -> Result.retry()
            RestoreOutcome.NotFound -> Result.failure()
        }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Book restores", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while books are restored from your cloud library"
                setShowBadge(false)
            }
        )
    }

    private fun foregroundInfo(title: String, stage: String, received: Long, total: Long): ForegroundInfo {
        val notification = notification(title, stage, received, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun updateForeground(title: String, stage: String, received: Long, total: Long) {
        // Notification permission can be denied independently of foreground execution. A missing
        // drawer notification must never be allowed to kill the actual restore.
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(
                notificationId, notification(title, stage, received, total)
            )
        }
    }

    private fun notification(title: String, stage: String, received: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.lantern.library.R.drawable.ic_lore_notification)
            .setContentTitle("Restoring “${title.ifBlank { "book" }}”")
            .setContentText(if (total > 0L) "$stage · ${Transfers.transferred(received, total)}" else stage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (total > 0L) "$stage · ${Transfers.transferred(received, total)}" else stage
            ))
            .setProgress(
                if (total > 0L) 100 else 0,
                if (total > 0L) ((received.coerceAtLeast(0L) * 100L) / total).toInt().coerceIn(0, 100) else 0,
                total <= 0L
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, notificationId,
                    Intent(applicationContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("lore_notification_id", "")
                        putExtra(
                            "lore_notification_target",
                            "reader:${inputData.getString(RestoreJobs.KEY_BOOK).orEmpty()}"
                        )
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun showNotice(title: String, message: String) {
        NotificationManagerCompat.from(applicationContext).cancel(notificationId)
        NotificationCenter.post(
            applicationContext, LoreNoticeCategory.DOWNLOADS,
            "Restore needs attention", "“$title”: $message",
            restoreNotificationEventId("attention", inputData.getString(RestoreJobs.KEY_NAMESPACE).orEmpty(), inputData.getString(RestoreJobs.KEY_BOOK).orEmpty(), notificationEventScope, runAttemptCount),
            "library"
        )
    }

    companion object { private const val CHANNEL = "lore_restore_progress" }
}

/** File-backed host: no Activity, Compose state, ViewModel or viewModelScope is involved. */
private class RestoreWorkerHost(
    private val context: Context,
    private val profiles: ProfileStore,
    private val namespace: String,
    private val libraryFile: File,
    private val token: String,
    private val progress: (String, Long, Long) -> Unit
) : DownloadHost {

    override fun latest(bookId: String): LibraryBook? =
        BookRecords.read(libraryFile).firstOrNull { it.id == bookId }

    override fun persist(book: LibraryBook): Boolean =
        BookRecords.update(libraryFile, book.id) { book }

    override val booksDir: File get() = BookIo.booksDir(context)

    override suspend fun accountHolds(book: LibraryBook): AccountLink = AccountLink.UNKNOWN
    override fun restorePaused(bookId: String): Boolean =
        bookId in profiles.preferences(namespace).getStringSet("paused_restores", emptySet()).orEmpty()
    override fun restoreCancelled(bookId: String): Boolean = false
    override fun deleteLocal(file: File): Boolean = runCatching { file.delete() && !file.exists() }.getOrDefault(false)

    override suspend fun fetchCloud(book: LibraryBook, dest: File): Boolean {
        val id = book.driveFileId ?: return false
        return DriveLibrary.download(token, id, dest, book.format) is DriveOutcome.Ok
    }

    override suspend fun streamCloud(
        book: LibraryBook, dest: File, from: Long, control: RestoreControl,
        onBytes: (received: Long, total: Long) -> Unit
    ): StreamReport {
        val id = book.driveFileId ?: return StreamReport(0L, -1L, false, false, false, "this book has no cloud file")
        return DriveLibrary.streamDownload(token, book.id, id, dest, from, control, onBytes)
    }

    override fun resumeOffset(bookId: String): Long =
        restoreJournal()[bookId]?.first ?: 0L

    override fun noteRestore(bookId: String, received: Long, total: Long) {
        synchronized(RestoreJournalLock.lock) {
            val current = restoreJournal().toMutableMap()
            current[bookId] = received to total
            writeRestoreJournal(current)
        }
    }

    override fun clearRestore(bookId: String) {
        synchronized(RestoreJournalLock.lock) {
            val current = restoreJournal()
            if (bookId in current) writeRestoreJournal(current - bookId)
        }
    }

    override fun noteRestoreProgress(bookId: String, received: Long, total: Long, phase: TransferPhase) {
        when (phase) {
            TransferPhase.PREPARING -> {
                TransferBus.restorePreparing(bookId, total.coerceAtLeast(0L))
                progress("Preparing download", received, total)
            }
            TransferPhase.UPLOADING -> {
                TransferBus.restoring(bookId, received, total.coerceAtLeast(0L))
                progress("Downloading", received, total)
            }
            TransferPhase.VERIFYING -> {
                TransferBus.restoreVerifying(bookId, total.coerceAtLeast(0L))
                progress("Verifying file", total, total)
            }
            TransferPhase.PAUSED -> {
                TransferBus.paused(bookId, received, total, "paused", restore = true)
                progress("Paused", received, total)
            }
            TransferPhase.FAILED -> {
                TransferBus.failed(bookId, received, total, "restore failed")
                progress("Restore failed", received, total)
            }
        }
    }

    override fun refreshAudit() { /* The next foreground audit reads the durable record. */ }

    override fun note(message: String) {
        SyncDiag.mark("Background restore", message)
    }

    private fun restoreJournal(): Map<String, Pair<Long, Long>> = synchronized(RestoreJournalLock.lock) {
        val file = profiles.file(namespace, "restore_journal.json")
        if (!file.exists()) return emptyMap()
        runCatching {
            val arr = JSONArray(file.readText())
            buildMap {
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    if (id.isNotBlank()) put(id, row.optLong("received") to row.optLong("total"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeRestoreJournal(entries: Map<String, Pair<Long, Long>>) = synchronized(RestoreJournalLock.lock) {
        val file = profiles.file(namespace, "restore_journal.json")
        val array = JSONArray()
        entries.forEach { (id, values) ->
            array.put(JSONObject().put("id", id).put("received", values.first).put("total", values.second))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.writeText(array.toString()) }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
    }

}
