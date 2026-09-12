package com.lantern.library.data

import com.lantern.library.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GoogleBooks {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun apiKey(): String = BuildConfig.GOOGLE_BOOKS_API_KEY.trim()

    fun isConfigured(): Boolean = apiKey().isNotEmpty()

    suspend fun volumes(
        query: String,
        orderBy: String,
        maxResults: Int = 20,
        startIndex: Int = 0
    ): List<DiscoveryBook> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isEmpty() || query.isBlank()) {
            if (key.isEmpty()) RecDiag.log("volumes skipped: missing key")
            return@withContext emptyList()
        }
        RecDiag.requestStarted = true
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("www.googleapis.com")
            .addPathSegments("books/v1/volumes")
            .addQueryParameter("q", query)
            .addQueryParameter("orderBy", orderBy)
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 40).toString())
            .addQueryParameter("startIndex", startIndex.coerceAtLeast(0).toString())
            .addQueryParameter("printType", "books")
            .addQueryParameter("key", key)
            .build()
            .toString()
        repeat(3) { attempt ->
            val fetched = fetch(url)
            when {
                fetched.code == 200 && !fetched.body.isNullOrBlank() ->
                    return@withContext parseVolumes(fetched.body)
                fetched.code == 429 || fetched.code in 500..599 || fetched.code == -1 -> {
                    if (attempt == 2) return@withContext emptyList()
                    delay(400L * (attempt + 1) * (attempt + 1))
                }
                else -> return@withContext emptyList()
            }
        }
        emptyList()
    }

    private fun fetch(url: String): Fetch {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Lantern/3.5.0 (Android reader)")
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string()
                RecDiag.http(res.code, itemCount(body))
                Fetch(res.code, body)
            }
        } catch (_: Exception) {
            RecDiag.http(-1, 0)
            Fetch(-1, null)
        }
    }

    private fun itemCount(body: String?): Int {
        if (body.isNullOrBlank()) return 0
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return 0
        val items = root.optJSONArray("items")
        return if (items != null) items.length() else root.optInt("totalItems", 0)
    }

    private fun parseVolumes(json: String): List<DiscoveryBook> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<DiscoveryBook>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseVolume(item)?.let { out += it }
        }
        return out
    }

    private fun parseVolume(item: JSONObject): DiscoveryBook? {
        val id = item.optString("id").trim()
        if (id.isEmpty()) return null
        val info = item.optJSONObject("volumeInfo") ?: JSONObject()
        val title = info.optString("title").trim()
        if (title.isEmpty()) return null
        val authors = stringList(info.optJSONArray("authors"))
        val categories = stringList(info.optJSONArray("categories"))
        val cover = pickCover(info.optJSONObject("imageLinks"))
        val identifiers = info.optJSONArray("industryIdentifiers")
        var isbn: String? = null
        if (identifiers != null) {
            for (i in 0 until identifiers.length()) {
                val row = identifiers.optJSONObject(i) ?: continue
                val type = row.optString("type")
                val ident = row.optString("identifier").trim()
                if (ident.isEmpty()) continue
                if (type == "ISBN_13") {
                    isbn = ident
                    break
                }
                if (type == "ISBN_10" && isbn == null) isbn = ident
            }
        }
        val sale = item.optJSONObject("saleInfo")
        val access = item.optJSONObject("accessInfo")
        return DiscoveryBook(
            volumeId = id,
            title = title,
            authors = authors,
            description = info.optString("description").trim(),
            categories = categories,
            coverUrl = covers.firstOrNull(),
            coverUrls = covers,
            publishedDate = info.optString("publishedDate").trim(),
            averageRating = info.optDouble("averageRating", 0.0).toFloat(),
            ratingsCount = info.optInt("ratingsCount", 0),
            isbn = isbn,
            infoLink = httpsOrNull(info.optString("infoLink")),
            previewLink = httpsOrNull(info.optString("previewLink")),
            canonicalLink = httpsOrNull(info.optString("canonicalVolumeLink")),
            buyLink = httpsOrNull(sale?.optString("buyLink") ?: ""),
            publicDomain = access?.optBoolean("publicDomain", false) == true
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private fun pickCover(images: JSONObject?): List<String> {
        if (images == null) return emptyList()
        val fields = listOf("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail")
        val out = ArrayList<String>(fields.size)
        val seen = HashSet<String>()
        for (field in fields) {
            val raw = images.optString(field).trim()
            if (!raw.startsWith("http")) continue
            val url = https(raw)
            if (seen.add(url)) out += url
        }
        return out
    }

    private fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun httpsOrNull(url: String): String? {
        val t = url.trim()
        if (!t.startsWith("http")) return null
        return https(t)
    }

    private data class Fetch(val code: Int, val body: String?)
}
