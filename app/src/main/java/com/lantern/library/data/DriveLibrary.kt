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
    private const val FOLDER_NAME = "Lore Library"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
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

    fun clearCachedFolder() {
        synchronized(folderLock) {
            cachedFolderId = null
            cachedFolderOwner = null
        }
    }

    fun mimeFor(book: LibraryBook): String =
        if (book.format == BookFormat.PDF) "application/pdf" else "application/epub+zip"

    fun ensureFolder(token: String, ownerKey: String? = null): DriveOutcome<String> {
        synchronized(folderLock) {
            if (ownerKey != null && cachedFolderId != null && cachedFolderOwner == ownerKey) {
                return DriveOutcome.Ok(cachedFolderId!!)
            }
            if (cachedFolderOwner != ownerKey) {
                cachedFolderId = null
                cachedFolderOwner = null
            }
            when (val existing = query(
                token,
                "name='${escape(FOLDER_NAME)}' and mimeType='$FOLDER_MIME' and trashed=false"
            )) {
                QueryOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                QueryOutcome.Error -> return DriveOutcome.Failed
                is QueryOutcome.Ok -> existing.ids.firstOrNull()?.let {
                    if (ownerKey != null) {
                        cachedFolderId = it
                        cachedFolderOwner = ownerKey
                    }
                    return DriveOutcome.Ok(it)
                }
            }
            val body = JSONObject()
                .put("name", FOLDER_NAME)
                .put("mimeType", FOLDER_MIME)
                .toString()
                .toRequestBody(jsonType)
            val req = Request.Builder()
                .url(FILES)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            val raw = executeRaw(req) ?: return DriveOutcome.Failed
            if (raw.code == 401) return DriveOutcome.Unauthorized
            if (!raw.ok || raw.body == null) return DriveOutcome.Failed
            val id = JSONObject(raw.body).optString("id").takeIf { it.isNotBlank() }
                ?: return DriveOutcome.Failed
            if (ownerKey != null) {
                cachedFolderId = id
                cachedFolderOwner = ownerKey
            }
            return DriveOutcome.Ok(id)
        }
    }

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

    fun upload(token: String, folderId: String, book: LibraryBook, file: File): DriveOutcome<String> {
        val lock = synchronized(uploadLocks) { uploadLocks.getOrPut(book.id) { Any() } }
        synchronized(lock) {
            val q = "appProperties has { key='loreId' and value='${escape(book.id)}' } and trashed=false"
            when (val lookup = query(token, q)) {
                QueryOutcome.Unauthorized -> return DriveOutcome.Unauthorized
                QueryOutcome.Error -> return DriveOutcome.Failed
                is QueryOutcome.Ok -> {
                    val existing = lookup.ids.firstOrNull()
                    if (!existing.isNullOrBlank()) return DriveOutcome.Ok(existing)
                }
            }
            val meta = JSONObject()
                .put("name", driveName(book))
                .put("parents", JSONArray().put(folderId))
                .put("appProperties", JSONObject().put("loreId", book.id))
            val multipart = MultipartBody.Builder()
                .setType(relatedType)
                .addPart(
                    Headers.of("Content-Type", "application/json; charset=UTF-8"),
                    meta.toString().toRequestBody(jsonType)
                )
                .addPart(
                    Headers.of("Content-Type", mimeFor(book)),
                    file.asRequestBody(mimeFor(book).toMediaType())
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
    }

    fun download(token: String, fileId: String, dest: File): DriveOutcome<Unit> {
        val req = Request.Builder()
            .url("$FILES/${enc(fileId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { res ->
                if (res.code == 401) return@use DriveOutcome.Unauthorized
                if (!res.isSuccessful) return@use DriveOutcome.Failed
                val body = res.body ?: return@use DriveOutcome.Failed
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                try {
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (tmp.length() <= 0L) {
                        tmp.delete()
                        return@use DriveOutcome.Failed
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    DriveOutcome.Ok(Unit)
                } catch (_: Exception) {
                    runCatching { tmp.delete() }
                    DriveOutcome.Failed
                }
            }
        }.getOrDefault(DriveOutcome.Failed)
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
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=drive&fields=files(id)&pageSize=10"
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

private sealed class QueryOutcome {
    data class Ok(val ids: List<String>) : QueryOutcome()
    object Error : QueryOutcome()
    object Unauthorized : QueryOutcome()
}
