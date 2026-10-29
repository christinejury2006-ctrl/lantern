package com.lantern.library.data

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

    /** A `multipart/related` body from parts that already carry their own media types. */
    private fun related(vararg parts: okhttp3.RequestBody): MultipartBody =
        MultipartBody.Builder()
            .setType(relatedType)
            .apply { parts.forEach { addPart(it) } }
            .build()

    private fun createFile(token: String, book: LibraryBook, file: File): DriveOutcome<String> {
        val meta = JSONObject()
            .put("name", driveName(book))
            .put("parents", JSONArray().put(APP_DATA_FOLDER))
            .put("appProperties", JSONObject().put("loreId", book.id))
        val multipart = related(
            jsonBody(meta.toString()),
            fileBody(file, mimeFor(book))
        )
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
        val multipart = related(
            jsonBody(meta.toString()),
            jsonBody(json)
        )
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
        val up = executeRaw(upReq)
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
    private fun describe(raw: RawHttp, what: String): String {
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
