package com.lantern.library.data

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        .build()

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

    private fun noteError(what: String, code: Int, body: String?) {
        val snippet = body.orEmpty().replace(Regex("\\s+"), " ").take(140)
        lastError = "$what failed: HTTP $code" + if (snippet.isBlank()) "" else " — $snippet"
    }

    fun clearError() {
        lastError = null
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
     * The previous version queried for an existing `loreId` and returned its id immediately, on the
     * assumption that "there is already a file" means "the right bytes are already there". That is
     * wrong in the one case that matters: a book whose local file was repaired, re-imported or
     * re-exported keeps its id and silently never updates its contents, so the reader restores an
     * old or broken copy on the next phone. Now a match is an instruction to **replace the media**
     * of that file, keeping the id and the `loreId` association, and the result is verified by
     * comparing Drive's checksum with the local file's before success is reported.
     */
    fun upload(token: String, folderId: String, book: LibraryBook, file: File): DriveOutcome<String> {
        if (!file.exists() || file.length() <= 0L) return DriveOutcome.Failed
        val lock = synchronized(uploadLocks) { uploadLocks.getOrPut(book.id) { Any() } }
        synchronized(lock) {
            val existing = when (val lookup = findByLoreId(token, book.id)) {
                LoreLookup.Error -> return DriveOutcome.Failed
                LoreLookup.NotFound -> null
                is LoreLookup.Found -> lookup.id
            }
            val id = if (existing != null) {
                when (val replaced = replaceMedia(token, existing, book, file)) {
                    is DriveOutcome.Ok -> replaced.value
                    DriveOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                    DriveOutcome.Failed -> return DriveOutcome.Failed
                }
            } else {
                when (val created = createFile(token, book, file)) {
                    is DriveOutcome.Ok -> created.value
                    DriveOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                    DriveOutcome.Failed -> return DriveOutcome.Failed
                }
            }
            // Confirm the bytes are there. A 200 from the upload endpoint is not the same thing as
            // the file being intact, and a backup that is not confirmed must not be reported.
            when (val check = verify(token, id, file)) {
                VerifyOutcome.Verified -> return DriveOutcome.Ok(id)
                VerifyOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                VerifyOutcome.Mismatch -> {
                    // One retry, because the usual cause is a transfer the service truncated.
                    when (replaceMedia(token, id, book, file)) {
                        is DriveOutcome.Ok -> Unit
                        DriveOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                        DriveOutcome.Failed -> return DriveOutcome.Failed
                    }
                    return when (verify(token, id, file)) {
                        VerifyOutcome.Verified -> DriveOutcome.Ok(id)
                        VerifyOutcome.Unauthorized -> DriveOutcome.Unauthorized
                        VerifyOutcome.Mismatch, VerifyOutcome.Unknown -> DriveOutcome.Failed
                    }
                }
                // A successful HTTP upload is not enough: if Drive cannot confirm the bytes, keep the book pending.
                VerifyOutcome.Unknown -> return DriveOutcome.Failed
            }
        }
    }

    private fun createFile(token: String, book: LibraryBook, file: File): DriveOutcome<String> {
        val meta = JSONObject()
            .put("name", driveName(book))
            .put("parents", JSONArray().put(APP_DATA_FOLDER))
            .put("appProperties", JSONObject().put("loreId", book.id))
        val multipart = MultipartBody.Builder()
            .setType(relatedType)
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                meta.toString().toRequestBody(jsonType)
            )
            .addPart(
                Headers.headersOf("Content-Type", mimeFor(book)),
                file.asRequestBody(mimeFor(book).toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        val raw = executeRaw(req) ?: return DriveOutcome.Failed.also { noteError("upload", 0, "no response") }
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
    private fun replaceMedia(token: String, fileId: String, book: LibraryBook, file: File): DriveOutcome<String> {
        val req = Request.Builder()
            .url("$UPLOAD/${enc(fileId)}?uploadType=media")
            .header("Authorization", "Bearer $token")
            .patch(file.asRequestBody(mimeFor(book).toMediaType()))
            .build()
        val raw = executeRaw(req) ?: return DriveOutcome.Failed.also { noteError("replace", 0, "no response") }
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

    private fun md5Of(file: File): String = java.security.MessageDigest.getInstance("MD5").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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
        return runCatching {
            http.newCall(req).execute().use { res ->
                if (res.code == 401) return@use DriveOutcome.Unauthorized
                if (res.code == 404) return@use DriveOutcome.Failed
                if (!res.isSuccessful) return@use DriveOutcome.Failed
                val body = res.body ?: return@use DriveOutcome.Failed
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                try {
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (tmp.length() < BookFiles.MIN_BYTES) {
                        tmp.delete()
                        return@use DriveOutcome.Failed
                    }
                    val expected = format ?: formatFromName(dest.name)
                    if (expected != null) {
                        when (val check = BookFiles.validate(tmp, expected)) {
                            is BookFiles.Check.Invalid -> {
                                tmp.delete()
                                return@use DriveOutcome.Failed
                            }
                            BookFiles.Check.Ok -> Unit
                        }
                    }
                    if (!BookFiles.commit(tmp, dest)) {
                        tmp.delete()
                        return@use DriveOutcome.Failed
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
        val multipart = MultipartBody.Builder()
            .setType(relatedType)
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                meta.toString().toRequestBody(jsonType)
            )
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                json.toRequestBody(jsonType)
            )
            .build()
        val req = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        val raw = executeRaw(req) ?: return DriveOutcome.Failed
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
        val raw = executeRaw(req) ?: return DriveOutcome.Failed
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

    private fun query(token: String, q: String): QueryOutcome {
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=appDataFolder&fields=files(id)&pageSize=100"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val exec = executeRaw(req) ?: return QueryOutcome.Error
        if (exec.code == 401) return QueryOutcome.Unauthorized
        if (!exec.ok || exec.body == null) return QueryOutcome.Error
        val files = runCatching { JSONObject(exec.body).optJSONArray("files") }.getOrNull()
            ?: return QueryOutcome.Error
        val ids = ArrayList<String>(files.length())
        for (i in 0 until files.length()) {
            val id = files.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) ids += id
        }
        return QueryOutcome.Ok(ids)
    }

    private class RawHttp(val code: Int, val body: String?) {
        val ok: Boolean get() = code in 200..299
    }

    private fun executeRaw(req: Request): RawHttp? {
        return try {
            http.newCall(req).execute().use { res ->
                RawHttp(res.code, res.body?.string())
            }
        } catch (_: Exception) {
            null
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
