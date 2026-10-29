package com.lantern.library.data

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object DriveLibrary {
    private const val APP_DATA_FOLDER = "appDataFolder"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private val jsonType = "application/json; charset=UTF-8".toMediaType()
    private val relatedType = "multipart/related".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // A dropped keep-alive socket is the single most common cause of a book upload failing on
        // a phone. OkHttp can retry those itself, before the app-level retry even sees them.
        .retryOnConnectionFailure(true)
        .build()

    /** One HTTP answer, with the body kept as bytes so a media download can be hashed. */
    internal class Raw(val code: Int, val bytes: ByteArray?) {
        val ok: Boolean get() = code in 200..299
        /** Decoded once, and a `val`, so call sites keep their null-check smart casts. */
        val body: String? = bytes?.toString(Charsets.UTF_8)
    }

    /**
     * The single place a request leaves this object.
     *
     * An interface, not a private method, because the upload path is the one piece of this app
     * that cannot be tested on a phone without a phone: CI needs to drive a successful EPUB
     * upload, a flaky one, and a retry that must not create a second Drive file, with no Google
     * account and no network. `null` from [call] means the call never completed at all — a
     * timeout, a dropped socket, DNS — which is the exact failure the device reported as
     * "HTTP 0 — No response from Google Drive".
     */
    internal interface Transport {
        fun call(req: Request): Raw?
    }

    private object OkHttpTransport : Transport {
        override fun call(req: Request): Raw? = try {
            http.newCall(req).execute().use { res -> Raw(res.code, res.body?.bytes()) }
        } catch (_: Exception) {
            null
        }
    }

    @Volatile internal var transport: Transport = OkHttpTransport

    /** Backoff waits through this, so a retry test runs in milliseconds instead of seconds. */
    @Volatile internal var sleeper: (Long) -> Unit = { millis ->
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Puts the real client back. Used by tests, and defensive in release. */
    internal fun resetTransport() {
        transport = OkHttpTransport
        sleeper = { millis ->
            try {
                Thread.sleep(millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastFailure = null
        lastCode = 0
        lastError = null
    }

    private val folderLock = Any()
    @Volatile private var cachedFolderId: String? = null
    @Volatile private var cachedFolderOwner: String? = null
    private val uploadLocks = HashMap<String, Any>()

    /**
     * Why the last Drive call failed, in words the reader can act on.
     *
     * "6 failed" with no reason is not a diagnosis. A 403 here almost always means the Drive API
     * is not enabled for the project, or the account never granted the app its hidden app-data
     * scope; a 401 means the token was rejected. Both are invisible from the phone otherwise.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** The HTTP status behind [lastError], or 0 when the call never completed. */
    @Volatile
    var lastCode: Int = 0
        private set

    /**
     * The last failed transfer, in the terms a reader can act on.
     *
     * A count ("2 failed") is not a diagnosis, and neither is a bare status code: the reader needs
     * to know *which book*, *what was attempted*, *why it stopped*, and *whether it will be tried
     * again*. No token, no file contents, no personal data beyond the book's own title.
     */
    data class TransferFailure(
        val bookId: String,
        val title: String,
        val operation: String,
        val reason: String,
        val retryable: Boolean
    )

    @Volatile
    var lastFailure: TransferFailure? = null
        private set

    private fun noteError(what: String, code: Int, body: String?) {
        val snippet = body.orEmpty().replace(Regex("\\s+"), " ").take(140)
        lastCode = code
        lastError = "$what failed: HTTP $code" + if (snippet.isBlank()) "" else " — $snippet"
    }

    private fun noteBookFailure(book: LibraryBook?, what: String, code: Int, detail: String?, retryable: Boolean) {
        val cls = RetryPolicy.classify(code)
        lastFailure = TransferFailure(
            bookId = book?.id.orEmpty(),
            title = book?.title?.takeIf { it.isNotBlank() } ?: "Untitled",
            operation = what,
            reason = RetryPolicy.explain(cls, code, detail.orEmpty()),
            retryable = retryable
        )
    }

    fun clearError() {
        lastError = null
        lastCode = 0
        lastFailure = null
    }

    fun clearCachedFolder() {
        synchronized(folderLock) {
            cachedFolderId = null
            cachedFolderOwner = null
        }
    }

    fun mimeFor(book: LibraryBook): String =
        if (book.format == BookFormat.PDF) "application/pdf" else "application/epub+zip"

    /** Google Drive's hidden appDataFolder is private to this app and needs drive.appdata scope. */
    fun ensureFolder(token: String, ownerKey: String? = null): DriveOutcome<String> =
        DriveOutcome.Ok(APP_DATA_FOLDER)

    fun findByLoreId(token: String, loreId: String): LoreLookup {
        val q = "appProperties has { key='loreId' and value='${escape(loreId)}' } and trashed=false"
        return when (val result = query(token, q)) {
            QueryOutcome.Unauthorized, QueryOutcome.Error -> LoreLookup.Error
            is QueryOutcome.Ok -> {
                val id = result.ids.firstOrNull()
                if (id.isNullOrBlank()) LoreLookup.NotFound else LoreLookup.Found(id)
            }
        }
    }

    /**
     * Puts [file] in the account's Drive and returns the id of the file that now holds it.
     *
     * The order of operations is the whole of the fix for "0 confirmed, 2 failed of 2":
     *
     * 1. **Look the book up by its `loreId` first.** That makes the operation idempotent. If a
     *    previous attempt created the file and then died before the answer arrived — the classic
     *    case on a phone network — the retry *replaces the media of that file* instead of creating
     *    a second copy of the same book.
     * 2. **Send the bytes**, by `multipart` create or by `uploadType=media` replace.
     * 3. **Prove the bytes are there** by comparing Drive's MD5 with the local file's. If Drive
     *    reports no checksum, download it and compare that way. A 200 from the upload endpoint is
     *    not a verified backup and is never reported as one.
     * 4. **Retry transient failures only**, with exponential backoff: no response, a timeout, 408,
     *    429, or a 5xx. A 401 re-authenticates; a 403 or 404 will not improve by being repeated.
     *
     * @param onAttempt called before each retry, with the attempt number and the reason.
     */
    fun upload(
        token: String,
        folderId: String,
        book: LibraryBook,
        file: File,
        onAttempt: (attempt: Int, reason: String) -> Unit = { _, _ -> },
        /** Bytes of the file actually written to the socket, in order, for this attempt. */
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        /** Called once the bytes are out and the account is being asked to prove it has them. */
        onVerifying: () -> Unit = {}
    ): DriveOutcome<String> {
        if (!file.exists() || file.length() <= 0L) {
            noteError("upload", 0, "the local file is missing or empty")
            noteBookFailure(book, "upload", 0, "the local file is missing or empty", retryable = false)
            return DriveOutcome.Failed
        }
        var attempt = 1
        while (true) {
            when (val once = uploadOnce(token, book, file, onProgress, onVerifying)) {
                is Attempt.Ok -> return DriveOutcome.Ok(once.id)
                Attempt.Unauthorized -> return DriveOutcome.Unauthorized
                is Attempt.Failed -> {
                    val retry = once.retryable && RetryPolicy.shouldRetry(attempt, RetryPolicy.classify(once.code))
                    noteBookFailure(book, "upload", once.code, once.detail, retry)
                    if (!retry) return DriveOutcome.Failed
                    onAttempt(attempt, RetryPolicy.explain(RetryPolicy.classify(once.code), once.code, once.detail))
                    sleeper(RetryPolicy.delayMs(attempt))
                    attempt++
                }
            }
        }
    }

    /** One attempt, with no sleeping: the retry decision belongs to [upload]. */
    private fun uploadOnce(
        token: String,
        book: LibraryBook,
        file: File,
        onProgress: (Long, Long) -> Unit,
        onVerifying: () -> Unit
    ): Attempt {
        val total = file.length()
        val report: (Long) -> Unit = { sent -> onProgress(sent, total) }
        // Idempotency first: an interrupted earlier attempt may already have created this file.
        val existing = when (val lookup = findByLoreId(token, book.id)) {
            LoreLookup.Error -> return Attempt.Failed(lastCode, lastError.orEmpty(), true)
            LoreLookup.NotFound -> null
            is LoreLookup.Found -> lookup.id
        }
        val id = if (existing != null) {
            when (val replaced = replaceMedia(token, existing, book, file, report)) {
                is DriveOutcome.Ok -> replaced.value
                DriveOutcome.Unauthorized -> return Attempt.Unauthorized
                DriveOutcome.Failed -> return Attempt.Failed(lastCode, lastError.orEmpty(), true)
            }
        } else {
            when (val created = createFile(token, book, file, report)) {
                is DriveOutcome.Ok -> created.value
                DriveOutcome.Unauthorized -> return Attempt.Unauthorized
                DriveOutcome.Failed -> return Attempt.Failed(lastCode, lastError.orEmpty(), true)
            }
        }
        // Every byte is out. That is not a backup yet, and nothing above this line may say it is.
        onVerifying()
        return when (val check = verify(token, id, file)) {
            VerifyOutcome.Verified -> Attempt.Ok(id)
            VerifyOutcome.Unauthorized -> Attempt.Unauthorized
            VerifyOutcome.Mismatch -> {
                // One re-send, because the usual cause is a transfer the service truncated.
                when (replaceMedia(token, id, book, file, report)) {
                    is DriveOutcome.Ok -> Unit
                    DriveOutcome.Unauthorized -> return Attempt.Unauthorized
                    DriveOutcome.Failed -> return Attempt.Failed(lastCode, lastError.orEmpty(), true)
                }
                when (val again = verify(token, id, file)) {
                    VerifyOutcome.Verified -> Attempt.Ok(id)
                    VerifyOutcome.Unauthorized -> Attempt.Unauthorized
                    VerifyOutcome.Mismatch -> Attempt.Failed(
                        0,
                        "the account's copy still differs from the file after two uploads",
                        true
                    )
                    // Still no checksum from Drive: settle it by downloading the bytes.
                    VerifyOutcome.Unknown -> deepVerify(token, id, file)
                }
            }
            // The upload said 200 but Drive gave nothing to compare. Do not call that verified;
            // fetch the file back and compare the bytes themselves.
            VerifyOutcome.Unknown -> deepVerify(token, id, file)
        }
    }

    /** Whether a verify attempt succeeded, in the shape [uploadOnce] needs. */
    private sealed class Attempt {
        data class Ok(val id: String) : Attempt()
        object Unauthorized : Attempt()
        data class Failed(val code: Int, val detail: String, val retryable: Boolean) : Attempt()
    }

    /**
     * Downloads the file that was just uploaded and compares it byte for byte with the source.
     *
     * `null` means the comparison could not be made — no response, or a refusal — which is a
     * failure, not a pass: an unprovable backup is not a backup.
     */
    private fun deepVerify(token: String, fileId: String, file: File): Attempt {
        val remote = downloadBytes(token, fileId)
            ?: return Attempt.Failed(lastCode, lastError.orEmpty().ifBlank { "could not read the uploaded file back" }, true)
        if (remote.size.toLong() != file.length()) {
            return Attempt.Failed(0, "read back ${remote.size} bytes, the file is ${file.length()}", true)
        }
        val same = java.security.MessageDigest.getInstance("MD5").run {
            update(remote)
            digest().contentEquals(md5BytesOf(file))
        }
        return if (same) Attempt.Ok(fileId) else Attempt.Failed(
            0,
            "the bytes in the account differ from the file on the phone",
            true
        )
    }

    /** The raw bytes of a file in the account, or null if they could not be read. */
    private fun downloadBytes(token: String, fileId: String): ByteArray? {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val raw = executeRaw(req) ?: run {
            noteError("read-back", 0, "no response")
            return null
        }
        if (!raw.ok) {
            noteError("read-back", raw.code, raw.body)
            return null
        }
        return raw.bytes
    }

    /**
     * The parts of a `multipart/related` upload.
     *
     * OkHttp 4 refuses a `Content-Type` in a part's header list — `MultipartBody.Part.create`
     * throws `IllegalArgumentException: Unexpected header: Content-Type` — because a part's media
     * type is its *body's* content type. Passing headers here is exactly what killed every account
     * sync the moment it tried to write the library snapshot, so the type now lives on the body and
     * the header list is left alone.
     */
    internal fun jsonBody(text: String): okhttp3.RequestBody = text.toRequestBody(jsonType)

    internal fun mediaBody(bytes: ByteArray, mime: String): okhttp3.RequestBody =
        bytes.toRequestBody(mime.toMediaType())

    internal fun fileBody(file: File, mime: String): okhttp3.RequestBody =
        file.asRequestBody(mime.toMediaType())

    /**
     * A request body that reports how many of its bytes have actually been written.
     *
     * This is where "progress" comes from: not a guess from the book's size, not a count of books
     * in the batch, but the number of bytes OkHttp has handed to the socket. It is wrapped around
     * *only the file part* of an upload, so the percentage is about the book and not about the
     * few hundred bytes of metadata in front of it.
     */
    internal class CountingBody(
        private val delegate: RequestBody,
        private val onProgress: (sent: Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun isOneShot(): Boolean = delegate.isOneShot()

        override fun writeTo(sink: BufferedSink) {
            val total = delegate.contentLength()
            var written = 0L
            val counting = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(if (total > 0L) written.coerceAtMost(total) else written)
                }
            }
            val forwarding = counting.buffer()
            delegate.writeTo(forwarding)
            // Flush before returning: OkHttp closes the sink itself, and an unflushed 8 KB tail
            // would otherwise be counted after the caller believes the body is finished.
            forwarding.flush()
        }
    }

    /** A `multipart/related` body from parts that already carry their own media types. */
    private fun related(vararg parts: okhttp3.RequestBody): MultipartBody =
        MultipartBody.Builder()
            .setType(relatedType)
            .apply { parts.forEach { addPart(it) } }
            .build()

    private fun createFile(
        token: String,
        book: LibraryBook,
        file: File,
        onProgress: (Long) -> Unit = {}
    ): DriveOutcome<String> {
        val meta = JSONObject()
            .put("name", driveName(book))
            .put("parents", JSONArray().put(APP_DATA_FOLDER))
            .put("appProperties", JSONObject().put("loreId", book.id))
        // The file part is the counted one; the metadata part is a few hundred bytes and would
        // make the percentage jump before any book data had moved.
        val multipart = related(
            jsonBody(meta.toString()),
            CountingBody(fileBody(file, mimeFor(book)), onProgress)
        )
        val req = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        // Not retried here: a repeated create would be a second copy of the book. [upload] owns
        // retries because it can look the book up again first.
        val raw = executeRaw(req, retries = false)
            ?: return DriveOutcome.Failed.also { noteError("upload", 0, "no response") }
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (!raw.ok || raw.body == null) {
            noteError("upload", raw.code, raw.body)
            return DriveOutcome.Failed
        }
        val id = JSONObject(raw.body).optString("id").takeIf { it.isNotBlank() }
            ?: return DriveOutcome.Failed.also { noteError("upload", raw.code, "no file id in response") }
        return DriveOutcome.Ok(id)
    }

    /** Overwrites an existing file's bytes, keeping its id, name and app properties. */
    private fun replaceMedia(
        token: String,
        fileId: String,
        book: LibraryBook,
        file: File,
        onProgress: (Long) -> Unit = {}
    ): DriveOutcome<String> {
        val req = Request.Builder()
            .url("$UPLOAD/${enc(fileId)}?uploadType=media")
            .header("Authorization", "Bearer $token")
            .patch(CountingBody(fileBody(file, mimeFor(book)), onProgress))
            .build()
        val raw = executeRaw(req, retries = false)
            ?: return DriveOutcome.Failed.also { noteError("replace", 0, "no response") }
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (!raw.ok) {
            noteError("replace", raw.code, raw.body)
            return DriveOutcome.Failed
        }
        return DriveOutcome.Ok(fileId)
    }

    /**
     * Whether the file already in the account holds exactly these bytes.
     *
     * `true` — no upload is needed; `false` — the remote copy is stale or broken and must be
     * replaced; `null` — the service would not say, in which case the caller should upload rather
     * than assume.
     *
     * This exists so that a library which was already backed up is *confirmed* rather than
     * re-uploaded on every sync: with a few hundred books, "verify then maybe upload" is the
     * difference between a second of metadata traffic and an hour of mobile data.
     */
    fun remoteMatches(token: String, fileId: String, file: File): Boolean? =
        when (verify(token, fileId, file)) {
            VerifyOutcome.Verified -> true
            VerifyOutcome.Mismatch -> false
            VerifyOutcome.Unauthorized, VerifyOutcome.Unknown -> null
        }

    /**
     * Compares Drive's checksum (or size, if the service does not report one) with the local file.
     * `Unknown` means the service gave us nothing to compare against; the caller then trusts the
     * upload rather than calling a good upload bad.
     */
    private fun verify(token: String, fileId: String, file: File): VerifyOutcome {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}?fields=id,size,md5Checksum")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val raw = executeRaw(req) ?: return VerifyOutcome.Unknown
        if (raw.code == 401) return VerifyOutcome.Unauthorized
        if (!raw.ok || raw.body == null) {
            // A refused checksum read is a failed backup, not an unknown one: say why.
            noteError("checksum check", raw.code, raw.body)
            return VerifyOutcome.Unknown
        }
        return runCatching {
            val json = JSONObject(raw.body)
            val remoteMd5 = json.optString("md5Checksum").takeIf { it.isNotBlank() }
            val remoteSize = json.optLong("size", -1L)
            if (remoteMd5 != null) {
                if (remoteMd5.equals(md5Of(file), ignoreCase = true)) VerifyOutcome.Verified else VerifyOutcome.Mismatch
            } else if (remoteSize >= 0L) {
                if (remoteSize == file.length()) VerifyOutcome.Verified else VerifyOutcome.Mismatch
            } else {
                VerifyOutcome.Unknown
            }
        }.getOrDefault(VerifyOutcome.Unknown)
    }

    private fun md5Of(file: File): String =
        md5BytesOf(file).joinToString("") { "%02x".format(it) }

    private fun md5BytesOf(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /**
     * Downloads a book and refuses to leave anything invalid in its place.
     *
     * The transfer lands in a `.part` file, is checked against the EPUB/PDF container rules in
     * [BookFiles], and only then replaces [dest] atomically. A captive-portal HTML page, an API
     * error document or a truncated transfer therefore never becomes a book, and never overwrites
     * one that was already there.
     */
    fun download(token: String, fileId: String, dest: File, format: BookFormat? = null): DriveOutcome<Unit> {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val raw = executeRaw(req)
        val detail: TransferFailure? = if (raw == null) {
            noteError("download", 0, "no response")
            TransferFailure("", "", "download", RetryPolicy.explain(TransferClass.TRANSIENT, 0, ""), true)
        } else if (!raw.ok) {
            noteError("download", raw.code, raw.body)
            TransferFailure(
                "", "", "download",
                RetryPolicy.explain(RetryPolicy.classify(raw.code), raw.code, raw.body.orEmpty()),
                RetryPolicy.classify(raw.code) == TransferClass.TRANSIENT
            )
        } else null
        if (detail != null) lastFailure = detail
        return runCatching {
            run {
                if (raw == null) return@run DriveOutcome.Failed
                if (raw.code == 401) return@run DriveOutcome.Unauthorized
                if (raw.code == 404) return@run DriveOutcome.Failed
                if (!raw.ok) return@run DriveOutcome.Failed
                val bytes = raw.bytes ?: return@run DriveOutcome.Failed
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                try {
                    tmp.writeBytes(bytes)
                    if (tmp.length() < BookFiles.MIN_BYTES) {
                        tmp.delete()
                        return@run DriveOutcome.Failed
                    }
                    val expected = format ?: formatFromName(dest.name)
                    if (expected != null) {
                        when (val check = BookFiles.validate(tmp, expected)) {
                            is BookFiles.Check.Invalid -> {
                                tmp.delete()
                                return@run DriveOutcome.Failed
                            }
                            BookFiles.Check.Ok -> Unit
                        }
                    }
                    if (!BookFiles.commit(tmp, dest)) {
                        tmp.delete()
                        return@run DriveOutcome.Failed
                    }
                    DriveOutcome.Ok(Unit)
                } catch (_: Exception) {
                    runCatching { tmp.delete() }
                    DriveOutcome.Failed
                }
            }
        }.getOrDefault(DriveOutcome.Failed)
    }

    private fun formatFromName(name: String): BookFormat? = when {
        name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
        name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
        name.endsWith(".txt", ignoreCase = true) -> BookFormat.TEXT
        else -> null
    }

    fun readJsonByLoreId(token: String, loreId: String): DriveOutcome<String?> {
        val q = "appProperties has { key='loreId' and value='${escape(loreId)}' } and trashed=false"
        return when (val result = query(token, q)) {
            QueryOutcome.Unauthorized -> DriveOutcome.Unauthorized
            QueryOutcome.Error -> DriveOutcome.Failed
            is QueryOutcome.Ok -> {
                val id = result.ids.firstOrNull()
                if (id.isNullOrBlank()) DriveOutcome.Ok(null)
                else downloadString(token, id)
            }
        }
    }

    fun writeJsonByLoreId(
        token: String,
        folderId: String,
        loreId: String,
        name: String,
        json: String
    ): DriveOutcome<String> {
        val q = "appProperties has { key='loreId' and value='${escape(loreId)}' } and trashed=false"
        when (val lookup = query(token, q)) {
            QueryOutcome.Unauthorized -> return DriveOutcome.Unauthorized
            QueryOutcome.Error -> return DriveOutcome.Failed
            is QueryOutcome.Ok -> {
                val existing = lookup.ids.firstOrNull()
                if (!existing.isNullOrBlank()) return patchJson(token, existing, json)
            }
        }
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject().put("loreId", loreId))
            .put("mimeType", "application/json")
        val multipart = related(
            jsonBody(meta.toString()),
            jsonBody(json)
        )
        val req = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        val raw = executeRaw(req, retries = false) ?: return DriveOutcome.Failed
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (!raw.ok || raw.body == null) return DriveOutcome.Failed
        val id = JSONObject(raw.body).optString("id").takeIf { it.isNotBlank() }
            ?: return DriveOutcome.Failed
        return DriveOutcome.Ok(id)
    }

    private fun patchJson(token: String, fileId: String, json: String): DriveOutcome<String> {
        val req = Request.Builder()
            .url("$UPLOAD/${enc(fileId)}?uploadType=media")
            .header("Authorization", "Bearer $token")
            .patch(json.toRequestBody(jsonType))
            .build()
        val raw = executeRaw(req, retries = false) ?: return DriveOutcome.Failed
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (!raw.ok) return DriveOutcome.Failed
        return DriveOutcome.Ok(fileId)
    }

    private fun downloadString(token: String, fileId: String): DriveOutcome<String?> {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val raw = executeRaw(req) ?: return DriveOutcome.Failed
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (raw.code == 404) return DriveOutcome.Ok(null)
        if (!raw.ok || raw.body == null) return DriveOutcome.Failed
        return DriveOutcome.Ok(raw.body)
    }

    fun delete(token: String, fileId: String): DriveOutcome<Unit> {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        val raw = executeRaw(req) ?: return DriveOutcome.Failed
        if (raw.code == 401) return DriveOutcome.Unauthorized
        if (raw.ok || raw.code == 404) return DriveOutcome.Ok(Unit)
        return DriveOutcome.Failed
    }

    /**
     * Proves, step by step, whether backup can work on this device right now.
     *
     * "I enabled the Drive API" is not something the app can take on trust, and the failure modes
     * below look identical from the outside: a token that arrives but lacks the app-data scope, a
     * Drive API that is disabled for the project, a scope that was never granted on the consent
     * screen. So the check does exactly what a real backup does — three small calls and one full
     * round trip — and reports each step with Google's own status code. The round trip matters:
     * uploading a probe, reading it back, comparing the bytes and deleting it exercises the same
     * code path a book takes, without touching a single book.
     */
    fun selfTest(token: String): List<Check> {
        val out = ArrayList<Check>(4)

        // 1. Is Drive reachable at all, and whose account is it?
        val aboutReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress),storageQuota(limit,usage)")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        when (val about = executeRaw(aboutReq)) {
            null -> out += Check("Drive API", false, "no response — the phone could not reach googleapis.com")
            else -> if (!about.ok) {
                out += Check("Drive API", false, describe(about, "about.get"))
            } else {
                val json = runCatching { JSONObject(about.body.orEmpty()) }.getOrNull()
                val email = json?.optJSONObject("user")?.optString("emailAddress").orEmpty()
                val quota = json?.optJSONObject("storageQuota")
                val limit = quota?.optString("limit").orEmpty()
                val usage = quota?.optString("usage").orEmpty()
                val used = if (limit.isNotBlank() && usage.isNotBlank()) {
                    " — ${usage.toLongOrNull()?.div(1_048_576) ?: 0} MB of " +
                        (limit.toLongOrNull()?.div(1_048_576)?.toString() ?: "unlimited") + " MB used"
                } else ""
                out += Check("Drive API", true, (email.ifBlank { "signed in" }) + used)
            }
        }

        // 2. Can the app see its own hidden app-data space? This is the scope question, separated
        //    from the "is the API enabled" question above.
        val listReq = Request.Builder()
            .url("$FILES?spaces=appDataFolder&fields=files(id,name)&pageSize=5")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        when (val list = executeRaw(listReq)) {
            null -> out += Check("App-data folder", false, "no response")
            else -> if (!list.ok) {
                out += Check("App-data folder", false, describe(list, "files.list"))
            } else {
                val count = runCatching {
                    JSONObject(list.body.orEmpty()).optJSONArray("files")?.length() ?: 0
                }.getOrDefault(0)
                out += Check("App-data folder", true, "$count file${if (count == 1) "" else "s"} visible")
            }
        }

        // 3. A real round trip with a throwaway file: create, read back, compare, delete.
        val probe = "lore-probe-" + System.currentTimeMillis() + ".txt"
        val payload = "Lore backup probe ${System.currentTimeMillis()}".toByteArray()
        val meta = JSONObject()
            .put("name", probe)
            .put("parents", JSONArray().put(APP_DATA_FOLDER))
            .put("appProperties", JSONObject().put("loreProbe", "1"))
        val multipart = related(
            jsonBody(meta.toString()),
            mediaBody(payload, "text/plain")
        )
        val upReq = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        val up = executeRaw(upReq, retries = false)
        val probeId = up?.let {
            if (it.ok) runCatching { JSONObject(it.body.orEmpty()).optString("id") }.getOrNull() else null
        }?.takeIf { it.isNotBlank() }
        if (probeId == null) {
            out += Check(
                "Upload round trip",
                false,
                if (up == null) "no response" else describe(up, "files.create")
            )
            return out
        }
        val downReq = Request.Builder()
            .url("$FILES/${enc(probeId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val down = executeRaw(downReq)
        val body = down?.body
        val same = down != null && down.ok && body == String(payload)
        runCatching { delete(token, probeId) }
        out += Check(
            "Upload round trip",
            same,
            when {
                down == null -> "upload worked, the read-back did not answer"
                !down.ok -> describe(down, "files.get")
                !same -> "uploaded ${payload.size} bytes, read back ${body?.length ?: 0} — the bytes differ"
                else -> "wrote, read back and deleted a ${payload.size}-byte test file"
            }
        )
        return out
    }

    /** One line of the connection test. */
    data class Check(val stage: String, val ok: Boolean, val detail: String)

    /** Google's status code plus its own words, in one line the reader can act on. */
    private fun describe(raw: Raw, what: String): String {
        val body = raw.body.orEmpty().replace(Regex("\\s+"), " ").take(220)
        val hint = when (raw.code) {
            401 -> " — the access token was rejected. Sign out and sign in again."
            403 -> if (body.contains("has not been used", true) || body.contains("disabled", true)) {
                " — the Google Drive API is not enabled for this project. " +
                    "Enable it in Google Cloud Console → APIs & Services → Library → Google Drive API."
            } else if (body.contains("insufficient", true) || body.contains("scope", true)) {
                " — the app was not granted the Drive app-data scope. Sign out, then sign in and " +
                    "allow Drive access when the consent screen appears."
            } else {
                " — Google refused the request."
            }
            404 -> " — not found."
            else -> ""
        }
        return "$what returned HTTP ${raw.code}$hint" + if (body.isBlank()) "" else " [$body]"
    }

    private fun query(token: String, q: String): QueryOutcome {
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=appDataFolder&fields=files(id)&pageSize=100"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val exec = executeRaw(req) ?: run {
            noteError("file search", 0, "No response from Google Drive. Check network connectivity.")
            return QueryOutcome.Error
        }
        if (exec.code == 401) {
            noteError("file search", exec.code, exec.body ?: "Google rejected the access token; re-authorize Drive access.")
            return QueryOutcome.Unauthorized
        }
        if (!exec.ok || exec.body == null) {
            noteError("file search", exec.code, exec.body)
            return QueryOutcome.Error
        }
        val files = runCatching { JSONObject(exec.body).optJSONArray("files") }.getOrNull()
            ?: run {
                noteError("file search", exec.code, "Google returned an unreadable response.")
                return QueryOutcome.Error
            }
        val ids = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val id = files.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) ids += id
        }
        return QueryOutcome.Ok(ids)
    }

    /**
     * One request, with bounded retries for failures that a retry can actually fix.
     *
     * The device reported `0 confirmed, 2 failed of 2` with "HTTP 0 — no response". That number
     * came from this function: the call threw, the exception was swallowed, and the book was
     * marked failed for good. Every read-only call here is safe to repeat, so a timeout gets three
     * more chances with 600 ms / 1.2 s / 2.4 s between them instead of one and done.
     *
     * Uploads are *not* retried here — they are retried in [upload], where each attempt re-looks-up
     * the book's `loreId` first, which is what keeps a retry from creating a duplicate file.
     */
    private fun executeRaw(req: Request, retries: Boolean = true): Raw? {
        var attempt = 1
        while (true) {
            val raw = transport.call(req)
            if (raw == null) {
                if (!retries || !RetryPolicy.shouldRetry(attempt, 0)) return null
                sleeper(RetryPolicy.delayMs(attempt))
                attempt++
                continue
            }
            if (!retries || !RetryPolicy.shouldRetry(attempt, raw.code)) return raw
            sleeper(RetryPolicy.delayMs(attempt))
            attempt++
        }
    }

    private fun driveName(book: LibraryBook): String {
        val ext = if (book.format == BookFormat.PDF) "pdf" else "epub"
        val safe = book.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { book.id }
        return "$safe.$ext"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun enc(id: String): String = URLEncoder.encode(id, "UTF-8")
}

sealed class DriveOutcome<out T> {
    data class Ok<T>(val value: T) : DriveOutcome<T>()
    object Unauthorized : DriveOutcome<Nothing>()
    object Failed : DriveOutcome<Nothing>()
}

sealed class LoreLookup {
    data class Found(val id: String) : LoreLookup()
    object NotFound : LoreLookup()
    object Error : LoreLookup()
}

private sealed class VerifyOutcome {
    object Verified : VerifyOutcome()
    object Mismatch : VerifyOutcome()
    object Unknown : VerifyOutcome()
    object Unauthorized : VerifyOutcome()
}

private sealed class QueryOutcome {
    data class Ok(val ids: List<String>) : QueryOutcome()
    object Error : QueryOutcome()
    object Unauthorized : QueryOutcome()
}
