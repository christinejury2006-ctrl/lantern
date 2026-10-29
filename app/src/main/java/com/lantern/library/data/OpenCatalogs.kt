package com.lantern.library.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A book that can be downloaded from a public catalogue, straight into the library.
 *
 * [id] is stable per edition, not per download, so the same edition found twice does not become
 * two books. [url] points at a file whose format is [format]; nothing here guesses.
 */
data class FreeEdition(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val url: String,
    val coverUrl: String? = null,
    /** Human-readable source, shown on the card: "Project Gutenberg", "Internet Archive". */
    val source: String,
    /** What the reader is allowed to do with it, in one line. */
    val rights: String
)

/**
 * Explore's fetching half: find a real, legal file for a book and hand back its URL.
 *
 * ## Which sites this actually uses, and why
 *
 * Three catalogues, all of them open, all of them with an API that publishes download links:
 *
 * - **Project Gutenberg** through **Gutendex** (`gutendex.com`). A public JSON mirror of
 *   Gutenberg's own catalogue, including direct `application/epub+zip` and `application/pdf`
 *   links. This is the primary source: the books are public domain, the files are hosted by
 *   Gutenberg, and the API asks for nothing but a User-Agent.
 * - **Internet Archive** (`archive.org`). Search through `advancedsearch.php`, then read
 *   `metadata/{identifier}` to see which files an item actually contains, and download those
 *   files from `download/{identifier}/{file}`. Only items whose metadata marks them as public
 *   downloads are offered. This is where most scanned public-domain books live.
 * - **Open Library** (`openlibrary.org`) is used only as a search index in front of the Archive:
 *   its `search.json` maps a title to Archive identifiers, which are then resolved against
 *   `archive.org/metadata`. No file is ever taken from Open Library itself.
 *
 * ## What this deliberately does not do
 *
 * Z-Library, Ocean of PDF, Library Genesis and their relatives host in-copyright books without
 * permission. Fetching from them is not something that can be shipped: it is infringement in most
 * of the world, the sites are frequently seized or blocked, the files are unverified, and the app
 * would be downloading executable-adjacent content from an untrusted host while telling the reader
 * it is a library. So there is no scraper here, and no "paste a link and we will parse it" hole
 * either — that is the same thing with the reader holding the bag.
 *
 * What the reader *can* do for an in-copyright book is exactly what a bookstore does: find it,
 * save it to Want to read, and open the store's page. That path already exists in Explore.
 *
 * ## Honesty about what a hit means
 *
 * A hit means an open catalogue claims to host this title. The URL is then downloaded and the file
 * is validated by [BookFiles] before it enters the library — a 404 page, a login wall or an HTML
 * error dressed up as an EPUB is rejected, and the reader is told the download failed rather than
 * being handed an empty book.
 */
object OpenCatalogs {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA = "Lore/2.0 (Android reader; +https://github.com/christinejury2006-ctrl/lantern)"

    /** Hard ceiling on one book. A larger file is a mislabelled scan bundle, not a book. */
    private const val MAX_BYTES = 64L * 1024L * 1024L

    /** How many Archive items to resolve per search. Each costs one metadata request. */
    private const val ARCHIVE_ITEMS = 4

    /**
     * Finds downloadable editions of [query], best first.
     *
     * Failures are per-source: if the Archive is down, Gutenberg hits are still returned. The
     * caller never has to know which source broke, but it is also never told "no results" because
     * one host was unreachable — the two are different answers.
     */
    suspend fun find(query: String, author: String = ""): List<FreeEdition> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val gutenberg = runCatching { gutenberg(q) }.getOrDefault(emptyList())
        // If Gutenberg already has an exact title match there is no need to grind through the
        // Archive; it is the slower of the two and the editions are usually worse scans.
        val exact = gutenberg.any { Recommendations.normalize(it.title) == Recommendations.normalize(q) }
        val archive = if (exact) emptyList() else runCatching { archive(q, author) }.getOrDefault(emptyList())
        (gutenberg + archive).distinctBy { it.url }
    }

    /** Gutenberg, via Gutendex. EPUB preferred; PDF kept as a second edition when there is one. */
    private fun gutenberg(query: String): List<FreeEdition> {
        val hits = Gutendex.search(query, null)
        val out = ArrayList<FreeEdition>(hits.size * 2)
        for (hit in hits.take(6)) {
            val title = hit.title.ifBlank { query }
            hit.epub?.let { url ->
                out += FreeEdition(
                    id = "gutenberg:${hit.remoteId}:epub",
                    title = title,
                    author = hit.author,
                    format = BookFormat.EPUB,
                    url = url,
                    coverUrl = hit.cover,
                    source = "Project Gutenberg",
                    rights = "Public domain"
                )
            }
            hit.pdf?.let { url ->
                out += FreeEdition(
                    id = "gutenberg:${hit.remoteId}:pdf",
                    title = title,
                    author = hit.author,
                    format = BookFormat.PDF,
                    url = url,
                    coverUrl = hit.cover,
                    source = "Project Gutenberg",
                    rights = "Public domain"
                )
            }
        }
        return out
    }

    /**
     * Internet Archive, searched by title.
     *
     * Two requests per item is the cost of not guessing filenames: Archive download URLs are
     * `download/{id}/{name}` where `name` comes from the item's own file list, and plenty of items
     * are scans with no EPUB at all. Only [`format`]s that are actually readable by the reader are
     * offered.
     */
    private fun archive(query: String, author: String): List<FreeEdition> {
        val q = buildString {
            append("title:(").append(query.replace(Regex("[\"()]"), " ").trim()).append(")")
            append(" AND mediatype:texts")
            append(" AND (format:EPUB OR format:\"Text PDF\")")
        }
        val searchUrl = "https://archive.org/advancedsearch.php" +
            "?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator" +
            "&rows=$ARCHIVE_ITEMS&page=1&output=json"
        val body = get(searchUrl) ?: return emptyList()
        val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<FreeEdition>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            val title = doc.optString("title").ifBlank { query }
            val creator = doc.optString("creator").ifBlank { author }
            out += editionsFor(identifier, title, creator)
        }
        return out
    }

    /** Resolves one Archive identifier into the editions the reader can actually open. */
    private fun editionsFor(identifier: String, title: String, author: String): List<FreeEdition> {
        val meta = get("https://archive.org/metadata/$identifier") ?: return emptyList()
        val root = runCatching { JSONObject(meta) }.getOrNull() ?: return emptyList()
        // `access-restricted-item` is the Archive's own flag for lending-only material. A lending
        // book has files, but the reader could not open one, so it is not offered as a download.
        if (root.optJSONObject("metadata")?.optBoolean("access-restricted-item", false) == true) return emptyList()
        val files = root.optJSONArray("files") ?: return emptyList()
        var epub: String? = null
        var pdf: String? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            if (name.isBlank()) continue
            val format = f.optString("format").lowercase()
            if (epub == null && (format == "epub" || name.endsWith(".epub", true))) epub = name
            if (pdf == null && (format == "text pdf" || format == "additional text pdf")) pdf = name
        }
        val out = ArrayList<FreeEdition>(2)
        val cover = "https://archive.org/services/img/$identifier"
        if (epub != null) {
            out += FreeEdition(
                id = "archive:$identifier:epub",
                title = title,
                author = author,
                format = BookFormat.EPUB,
                url = "https://archive.org/download/$identifier/$epub",
                coverUrl = cover,
                source = "Internet Archive",
                rights = "Public domain scan"
            )
        }
        if (pdf != null) {
            out += FreeEdition(
                id = "archive:$identifier:pdf",
                title = title,
                author = author,
                format = BookFormat.PDF,
                url = "https://archive.org/download/$identifier/$pdf",
                coverUrl = cover,
                source = "Internet Archive",
                rights = "Public domain scan"
            )
        }
        return out
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.body?.string()
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Downloads [url] as bytes.
     *
     * Capped at 64 MB: an open catalogue file larger than that is a mislabelled scan bundle, and
     * the reader's phone should not find out by running out of storage.
     */
    fun fetchBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body ?: return null
            // Trust the declared length when there is one: no point downloading 300 MB first.
            val declared = body.contentLength()
            if (declared > MAX_BYTES) return null
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    } catch (t: Throwable) {
        null
    }
}
