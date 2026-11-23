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
import com.lantern.library.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
    fun enqueue(context: Context, namespace: String, accountId: String, bookId: String, keepOffline: Boolean = true) {
        if (!Profiles.isValidNamespace(namespace) || namespace == Profiles.GUEST ||
            accountId.isBlank() || bookId.isBlank()
        ) return
        val mobileAllowed = ProfileStore(context.applicationContext as Application)
            .preferences(namespace).getBoolean("mobile", false)
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(RestoreJobs.data(namespace, accountId, bookId, keepOffline))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(
                    if (mobileAllowed) NetworkType.CONNECTED else NetworkType.UNMETERED
                ).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(RestoreJobs.TAG_ALL)
            .addTag("restore-account:$accountId")
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RestoreJobs.name(namespace, bookId),
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
        }
    }

    /** Waits for the unique restore already queued for this book, including one started elsewhere. */
    suspend fun await(context: Context, namespace: String, bookId: String): Boolean =
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            val name = RestoreJobs.name(namespace, bookId)
            while (true) {
                val infos = runCatching { manager.getWorkInfosForUniqueWork(name).get() }
                    .getOrDefault(emptyList())
                if (infos.isEmpty()) return@withContext false
                val active = infos.firstOrNull { !it.state.isFinished }
                if (active == null) return@withContext infos.last().state == androidx.work.WorkInfo.State.SUCCEEDED
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            false
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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

        val token = when (val auth = GoogleAuth.driveToken(applicationContext)) {
            is DriveTokenResult.Ok -> auth.token
            is DriveTokenResult.Recoverable -> {
                showNotice(book.title, "Open Lore to reconnect Google Drive before restoring this book.")
                return@withContext Result.failure()
            }
            DriveTokenResult.Unavailable -> return@withContext Result.retry()
        }

        ensureChannel()
        setForeground(foregroundInfo(book.title, "Preparing download", 0, book.backupBytes))
        val host = RestoreWorkerHost(applicationContext, profiles, namespace, libraryFile) { phase, received, total ->
            updateForeground(book.title, phase, received, total)
        }
        val outcome = DownloadService(host).restoreDownload(bookId, keepOffline = inputData.getBoolean(RestoreJobs.KEY_KEEP_OFFLINE, true))
        when (outcome) {
            is RestoreOutcome.Restored -> {
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                NotificationCenter.post(
                    applicationContext,
                    LoreNoticeCategory.DOWNLOADS,
                    "Book restored",
                    "“${outcome.book.title}” is available offline again.",
                    "restore-complete:$namespace:$bookId",
                    "reader:$bookId"
                )
                Result.success()
            }
            is RestoreOutcome.AlreadyAvailable -> {
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                Result.success()
            }
            is RestoreOutcome.Paused -> {
                NotificationManagerCompat.from(applicationContext).cancel(notificationId)
                NotificationCenter.post(
                    applicationContext, LoreNoticeCategory.DOWNLOADS, "Restore paused",
                    "“${book.title}” is paused. Your downloaded bytes are saved.",
                    "restore-paused:$namespace:$bookId", "library"
                )
                Result.success()
            }
            is RestoreOutcome.Refused -> {
                showNotice(book.title, outcome.blocker.message)
                Result.failure()
            }
            is RestoreOutcome.Failed -> {
                showNotice(book.title, "Could not restore: ${outcome.reason}")
                Result.failure()
            }
            RestoreOutcome.Busy -> Result.retry()
            RestoreOutcome.NotFound -> Result.failure()
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
        NotificationManagerCompat.from(applicationContext).notify(
            notificationId, notification(title, stage, received, total)
        )
    }

    private fun notification(title: String, stage: String, received: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.lantern.library.R.drawable.ic_lore_notification)
            .setContentTitle("Restoring “${title.ifBlank { "book" }}”")
            .setContentText(stage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(stage))
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
            "restore-attention:${inputData.getString(RestoreJobs.KEY_NAMESPACE)}:${inputData.getString(RestoreJobs.KEY_BOOK)}:${runAttemptCount}",
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
        val token = (GoogleAuth.driveToken(context) as? DriveTokenResult.Ok)?.token ?: return false
        return DriveLibrary.download(token, id, dest, book.format) is DriveOutcome.Ok
    }

    override suspend fun streamCloud(
        book: LibraryBook, dest: File, from: Long, control: RestoreControl,
        onBytes: (received: Long, total: Long) -> Unit
    ): StreamReport {
        val id = book.driveFileId ?: return StreamReport(0L, -1L, false, false, false, "this book has no cloud file")
        val token = when (val result = GoogleAuth.driveToken(context)) {
            is DriveTokenResult.Ok -> result.token
            else -> return StreamReport(0L, -1L, false, false, true, "Google Drive access is unavailable")
        }
        return DriveLibrary.streamDownload(token, book.id, id, dest, from, control, onBytes)
    }

    override fun resumeOffset(bookId: String): Long =
        restoreJournal()[bookId]?.first ?: 0L

    override fun noteRestore(bookId: String, received: Long, total: Long) {
        val current = restoreJournal().toMutableMap()
        current[bookId] = received to total
        writeRestoreJournal(current)
    }

    override fun clearRestore(bookId: String) {
        val current = restoreJournal()
        if (bookId in current) writeRestoreJournal(current - bookId)
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

    private fun restoreJournal(): Map<String, Pair<Long, Long>> = synchronized(JOURNAL_LOCK) {
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

    private fun writeRestoreJournal(entries: Map<String, Pair<Long, Long>>) = synchronized(JOURNAL_LOCK) {
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

    companion object { private val JOURNAL_LOCK = Any() }
}
