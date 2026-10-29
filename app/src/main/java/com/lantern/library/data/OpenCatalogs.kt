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
 * A place a book can be read or borrowed that is *not* a file this app can download.
 *
 * This exists because most books people search for are still in copyright. The honest answer for
 * those is not "not found" and it is not a pirate mirror: it is the place that legitimately lends
 * or sells the book. [action] is the verb on the button ("Borrow", "Read on Open Library", "Book
 * page"), so the reader is never told a title is downloadable when it is not.
 */
data class Elsewhere(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val source: String,
    val action: String,
    val url: String,
    val note: String
)

/**
 * Explore's fetching half: find a real, legal file for a book, and — when there is no legal file —
 * the place the book can actually be borrowed or opened.
 *
 * ## The sources, and what each one is for
 *
 * | Source | API | Gives | Reaches |
 * | --- | --- | --- | --- |
 * | **Project Gutenberg** via **Gutendex** | `gutendex.com/books` | EPUB + PDF links | 70,000+ public-domain classics |
 * | **Internet Archive** | `advancedsearch.php` + `metadata/{id}` | EPUB/PDF of *open* items only | Scanned public-domain books, and some modern open-access ones |
 * | **Open Library** | `search.json` | Work pages, covers, borrow links | 40M records — every modern book, mostly as *borrow* or *page* |
 * | **OpenAlex** | `api.openalex.org/works` | Direct OA PDFs | Current, in-copyright-year academic books published open access |
 * | **Google Books** | `volumes` (elsewhere in this app) | Descriptions, covers, store links | Anything in print, as metadata |
 *
 * ## What this deliberately does not do
 *
 * Z-Library, Anna's Archive, Library Genesis, Ocean of PDF and their relatives host in-copyright
 * books without permission. Fetching from them is not something that can be shipped: it is
 * infringement in most of the world, the hosts are frequently seized, the files are unverified, and
 * the app would be pulling bytes from an untrusted server while telling the reader it is a library.
 * So there is no scraper here and no "paste a link and we will parse it" hole either.
 *
 * The alternative is not a dead end. Every one of those sites exists because in-copyright books are
 * hard to get; this file answers that properly with [elsewhere]: the Internet Archive's
 * one-copy-at-a-time lending, Open Library's borrow, and the shop or publisher page. A reader can
 * read a 2019 novel through Explore — legally, in two taps, without this app holding the file.
 *
 * ## Honesty about what a hit means
 *
 * A download hit means an open catalogue claims to host this title. The URL is downloaded and the
 * file is validated by [BookFiles] before it enters the library — a 404 page, a login wall or an
 * HTML error dressed up as an EPUB is rejected, and the reader is told the download failed rather
 * than being handed an empty book.
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
    private const val ARCHIVE_ITEMS = 3

    /** How many Open Library records to look at. Each may cost one metadata request. */
    private const val OPEN_LIBRARY_HITS = 5

    /**
     * Everything downloadable for [query], best first.
     *
     * Failures are per-source: if the Archive is down, Gutenberg hits are still returned, and if
     * every catalogue is unreachable the caller gets an empty list rather than an exception — but
     * it is never told "no results" merely because one host was slow.
     */
    suspend fun search(query: String): List<FreeEdition> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val gutenberg = runCatching { gutenberg(q) }.getOrDefault(emptyList())
        val exact = gutenberg.any { Recommendations.normalize(it.title) == Recommendations.normalize(q) }
        // The Archive is the slowest of the sources (a second request per item to find the file
        // name), so it is skipped when a catalogue has already produced an exact title match.
        val archive = if (exact) emptyList() else runCatching { archive(q, "") }.getOrDefault(emptyList())
        val openLibrary = if (exact || (gutenberg + archive).size >= 4) {
            emptyList()
        } else {
            runCatching { openLibraryDownloads(q) }.getOrDefault(emptyList())
        }
        val openAccess = runCatching { openAlex(q) }.getOrDefault(emptyList())

        (gutenberg + archive + openLibrary + openAccess)
            .distinctBy { it.url }
            .sortedByDescending { Recommendations.normalize(it.title) == Recommendations.normalize(q) }
            .take(12)
    }

    /** Backwards-compatible name: the first version of this file called it `find`. */
    suspend fun find(query: String, author: String = ""): List<FreeEdition> = search(if (author.isBlank()) query else "$query $author")

    /**
     * Where [query] can be read if it cannot be downloaded.
     *
     * Deliberately returns at most a handful: one borrow link, one Open Library page, one shop page
     * and the archive page the book itself lives on. A wall of links is not an answer.
     */
    suspend fun elsewhere(query: String): List<Elsewhere> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val out = ArrayList<Elsewhere>()
        runCatching { openLibraryElsewhere(q, out) }
        runCatching { internetArchiveBorrow(q, out) }
        out.distinctBy { it.url }.take(8)
    }

    // ---------------------------------------------------------------- Project Gutenberg

    /** Gutenberg, via Gutendex. EPUB preferred; PDF kept as a second edition when there is one. */
    private suspend fun gutenberg(query: String): List<FreeEdition> {
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

    // ---------------------------------------------------------------- Internet Archive

    /**
     * Internet Archive, searched by title.
     *
     * Two requests per item is the cost of not guessing filenames: Archive download URLs are
     * `download/{id}/{name}` where `name` comes from the item's own file list, and plenty of items
     * are scans with no EPUB at all. Only formats the reader can actually open are offered.
     */
    private fun archive(query: String, author: String): List<FreeEdition> {
        val q = buildString {
            append("title:(").append(query.replace(Regex("[\"()]"), " ").trim()).append(")")
            append(" AND mediatype:texts")
            append(" AND (format:EPUB OR format:\"Text PDF\")")
        }
        val docs = archiveSearch(q, ARCHIVE_ITEMS)
        val out = ArrayList<FreeEdition>()
        for (doc in docs) {
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            out += editionsFor(
                identifier = identifier,
                title = doc.optString("title").ifBlank { query },
                author = doc.optString("creator").ifBlank { author }
            )
        }
        return out
    }

    /** Raw `advancedsearch.php` query → the `docs` array. */
    private fun archiveSearch(q: String, rows: Int): List<JSONObject> {
        val url = "https://archive.org/advancedsearch.php" +
            "?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator" +
            "&rows=$rows&page=1&output=json"
        val body = get(url) ?: return emptyList()
        val docs = runCatching { JSONObject(body).optJSONObject("response")?.optJSONArray("docs") }.getOrNull()
            ?: return emptyList()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
    }

    /** Resolves one Archive identifier into the editions the reader can actually open. */
    private fun editionsFor(identifier: String, title: String, author: String): List<FreeEdition> {
        val meta = get("https://archive.org/metadata/$identifier") ?: return emptyList()
        val root = runCatching { JSONObject(meta) }.getOrNull() ?: return emptyList()
        // `access-restricted-item` is the Archive's own flag for lending-only material. A lending
        // book has files, but the reader could not open one, so it is not offered as a download.
        val metadata = root.optJSONObject("metadata")
        if (metadata?.optBoolean("access-restricted-item", false) == true) return emptyList()
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
                rights = "Open item — public domain or openly licensed"
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
                rights = "Open item — public domain or openly licensed"
            )
        }
        return out
    }

    // ---------------------------------------------------------------- Open Library

    /**
     * Open Library's index, used two ways: to find Archive downloads for scanned public-domain
     * books, and to name the books that can only be borrowed.
     */
    private fun openLibraryDocs(query: String): List<JSONObject> {
        val fields = listOf(
            "key", "title", "author_name", "cover_i", "ia", "ebook_access", "first_publish_year"
        ).joinToString(",")
        val url = "https://openlibrary.org/search.json?q=" + URLEncoder.encode(query, "UTF-8") +
            "&limit=$OPEN_LIBRARY_HITS&fields=$fields"
        val body = get(url) ?: return emptyList()
        val docs = runCatching { JSONObject(body).optJSONArray("docs") }.getOrNull() ?: return emptyList()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
    }

    /** Downloads for Open Library records that are openly scanned, resolved through the Archive. */
    private fun openLibraryDownloads(query: String): List<FreeEdition> {
        val out = ArrayList<FreeEdition>()
        for (doc in openLibraryDocs(query)) {
            // `public` is Open Library's word for "the full scan is downloadable by anyone".
            val access = doc.optString("ebook_access")
            if (access != "public") continue
            val ia = doc.optJSONArray("ia") ?: continue
            val identifier = if (ia.length() > 0) ia.optString(0) else continue
            if (identifier.isBlank()) continue
            val authors = doc.optJSONArray("author_name")
            val author = if (authors != null && authors.length() > 0) authors.optString(0) else ""
            out += editionsFor(identifier, doc.optString("title").ifBlank { query }, author)
            if (out.size >= 4) break
        }
        return out
    }

    private fun openLibraryElsewhere(query: String, out: MutableList<Elsewhere>) {
        for (doc in openLibraryDocs(query)) {
            val title = doc.optString("title").ifBlank { query }
            val authors = doc.optJSONArray("author_name")
            val author = if (authors != null && authors.length() > 0) authors.optString(0) else ""
            val coverId = doc.optInt("cover_i", 0)
            val cover = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-M.jpg" else null
            val key = doc.optString("key")
            val ia = doc.optJSONArray("ia")
            val archiveId = if (ia != null && ia.length() > 0) ia.optString(0) else ""
            val access = doc.optString("ebook_access")

            // Borrowing is the Internet Archive's one-copy-at-a-time lending: legal, free, and the
            // only way an in-copyright book can be read in full without buying it.
            if (archiveId.isNotBlank() && (access == "borrowable" || access == "printdisabled")) {
                out += Elsewhere(
                    id = "ol-borrow:$archiveId",
                    title = title,
                    author = author,
                    coverUrl = cover,
                    source = "Internet Archive lending",
                    action = "Borrow",
                    url = "https://archive.org/details/$archiveId",
                    note = "Free with an Internet Archive account, one copy at a time"
                )
            }
            if (key.isNotBlank()) {
                out += Elsewhere(
                    id = "ol-page:$key",
                    title = title,
                    author = author,
                    coverUrl = cover,
                    source = "Open Library",
                    action = "Open Library page",
                    url = "https://openlibrary.org$key",
                    note = when (access) {
                        "public" -> "A downloadable scan is listed for this book"
                        "borrowable" -> "Listed for borrowing"
                        "no_ebook" -> "No ebook edition listed"
                        else -> "Catalogue record"
                    }
                )
            }
            if (out.size >= 6) return
        }
    }

    /** Archive items that are lendable, found directly, for books Open Library does not index well. */
    private fun internetArchiveBorrow(query: String, out: MutableList<Elsewhere>) {
        val q = "title:(" + query.replace(Regex("[\"()]"), " ").trim() + ") AND mediatype:texts"
        for (doc in archiveSearch(q, 2)) {
            val identifier = doc.optString("identifier")
            if (identifier.isBlank()) continue
            out += Elsewhere(
                id = "ia:$identifier",
                title = doc.optString("title").ifBlank { query },
                author = doc.optString("creator"),
                coverUrl = "https://archive.org/services/img/$identifier",
                source = "Internet Archive",
                action = "Open on Archive",
                url = "https://archive.org/details/$identifier",
                note = "Read or borrow on the Internet Archive"
            )
        }
    }

    // ---------------------------------------------------------------- Open access books

    /**
     * Current books, legally downloadable: OpenAlex's index of open-access *books*.
     *
     * This is the one source here that reaches books published this year. It is academic (OpenAlex
     * covers scholarly publishing), and it only ever offers a link where the publisher or an
     * institutional repository genuinely published the book open access — hence the `is_oa` filter
     * and the hard requirement for a `pdf_url`.
     */
    private fun openAlex(query: String): List<FreeEdition> {
        val url = "https://api.openalex.org/works?search=" + URLEncoder.encode(query, "UTF-8") +
            "&filter=type:book,is_oa:true&per-page=5" +
            "&select=id,display_name,publication_year,authorships,best_oa_location"
        val body = get(url) ?: return emptyList()
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<FreeEdition>()
        for (i in 0 until results.length()) {
            val w = results.optJSONObject(i) ?: continue
            val location = w.optJSONObject("best_oa_location")
            val pdf = location?.optString("pdf_url").orEmpty()
            if (pdf.isBlank() || !pdf.startsWith("http")) continue
            val authors = w.optJSONArray("authorships")
            val author = if (authors != null && authors.length() > 0) {
                authors.optJSONObject(0)?.optJSONObject("author")?.optString("display_name").orEmpty()
            } else ""
            val year = w.optInt("publication_year", 0)
            val id = w.optString("id").substringAfterLast('/')
            out += FreeEdition(
                id = "openalex:$id",
                title = w.optString("display_name").ifBlank { query },
                author = author,
                format = BookFormat.PDF,
                url = pdf,
                coverUrl = null,
                source = "Open access" + if (year > 0) " ($year)" else "",
                rights = "Published open access — free to read and share"
            )
        }
        return out
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Responses, kept briefly.
     *
     * One search fans out to a dozen requests across four services, and the same URLs are asked
     * again the moment the reader edits their query (the same author, the same Open Library
     * record). A five-minute cache turns that back into one round of traffic, and keeps the app
     * inside Gutendex's courtesy limit and Open Library's ~100-requests-per-5-minutes budget.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val CACHE_MS = 5 * 60 * 1000L
    private const val CACHE_MAX = 80

    private fun get(url: String): String? {
        val hit = cache[url]
        val now = System.currentTimeMillis()
        if (hit != null && now - hit.first < CACHE_MS) return hit.second
        val body = try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        } catch (t: Throwable) {
            null
        } ?: return null
        if (cache.size > CACHE_MAX) cache.clear()
        cache[url] = now to body
        return body
    }

    /**
     * Downloads [url] as bytes.
     *
     * Capped at [MAX_BYTES]: an open catalogue file larger than that is a mislabelled scan bundle,
     * and the reader's phone should not find out by running out of storage.
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
