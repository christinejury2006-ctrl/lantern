package com.lantern.library.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Gutendex {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val topics = listOf(
        "Fantasy", "Fiction", "Adventure", "Romance", "Mystery",
        "Science", "History", "Philosophy", "Poetry", "Children"
    )

    suspend fun search(query: String, topic: String?): List<CatalogBook> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val url = buildString {
            append("https://gutendex.com/books/?mime_type=application%2Fepub")
            if (q.isNotEmpty()) append("&search=").append(java.net.URLEncoder.encode(q, "UTF-8"))
            if (!topic.isNullOrBlank() && q.isEmpty()) {
                append("&topic=").append(java.net.URLEncoder.encode(topic.lowercase(), "UTF-8"))
            }
        }
        val body = get(url) ?: return@withContext emptyList()
        parse(body)
    }

    fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Lantern/2.0 (Android reader)")
            .build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.body?.string()
        }
    }

    fun downloadBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Lantern/2.0 (Android reader)")
            .build()
        return http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.body?.bytes()
        }
    }

    private fun parse(json: String): List<CatalogBook> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<CatalogBook>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val authors = o.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0) {
                authors.getJSONObject(0).optString("name", "Unknown")
            } else "Unknown"
            val formats = o.optJSONObject("formats") ?: JSONObject()
            val epub = firstUrl(formats, listOf("application/epub+zip", "application/epub"))
            val pdf = firstUrl(formats, listOf("application/pdf"))
            val cover = firstUrl(formats, listOf("image/jpeg"))
            if (epub == null && pdf == null) continue
            val subjects = o.optJSONArray("subjects")
            val subject = if (subjects != null && subjects.length() > 0) subjects.optString(0) else ""
            out += CatalogBook(
                remoteId = o.optInt("id"),
                title = o.optString("title"),
                author = author,
                cover = cover,
                epub = epub,
                pdf = pdf,
                subjects = subject,
                downloads = o.optInt("download_count")
            )
        }
        return out
    }

    private fun firstUrl(formats: JSONObject, keys: List<String>): String? {
        keys.forEach { k ->
            val v = formats.optString(k, "")
            if (v.startsWith("http")) return v
        }
        val names = formats.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (keys.any { k.startsWith(it) }) {
                val v = formats.optString(k, "")
                if (v.startsWith("http")) return v
            }
        }
        return null
    }
}
