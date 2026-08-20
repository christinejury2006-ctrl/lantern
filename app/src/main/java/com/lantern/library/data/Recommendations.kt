package com.lantern.library.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ln
import kotlin.random.Random

object Recommendations {
    private const val CACHE_FILE = "recommendations.json"
    private const val LIMIT = 24
    private val cacheLock = Any()

    fun localDay(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    fun cached(context: Context): List<DiscoveryBook>? =
        synchronized(cacheLock) { readUnlocked(context)?.books }

    fun excludeFromCache(context: Context, drop: (DiscoveryBook) -> Boolean) {
        synchronized(cacheLock) {
            val cache = readUnlocked(context) ?: return
            val kept = cache.books.filterNot(drop)
            if (kept.size == cache.books.size) return
            writeUnlocked(context, cache.day, kept)
        }
    }

    suspend fun daily(
        context: Context,
        library: () -> List<LibraryBook>,
        wantToRead: () -> List<DiscoveryBook>
    ): List<DiscoveryBook> {
        val today = localDay()
        val todayBooks = synchronized(cacheLock) {
            val cache = readUnlocked(context)
            if (cache != null && cache.day == today) cache.books else null
        }
        if (todayBooks != null) {
            return filterExcluded(todayBooks, library(), wantToRead())
        }
        if (!GoogleBooks.isConfigured() || !isOnline(context)) {
            val previous = synchronized(cacheLock) { readUnlocked(context)?.books.orEmpty() }
            return filterExcluded(previous, library(), wantToRead())
        }
        val built = withContext(Dispatchers.IO) {
            runCatching { buildPool(library(), wantToRead(), today) }.getOrNull()
        }
        return synchronized(cacheLock) {
            val existing = readUnlocked(context)
            val lib = library()
            val want = wantToRead()
            if (existing != null && existing.day == today) {
                val merged = filterExcluded(existing.books, lib, want)
                if (merged.size != existing.books.size) writeUnlocked(context, today, merged)
                merged
            } else if (built.isNullOrEmpty()) {
                filterExcluded(existing?.books.orEmpty(), lib, want)
            } else {
                val filtered = filterExcluded(built, lib, want)
                writeUnlocked(context, today, filtered)
                filtered
            }
        }
    }

    fun filterExcluded(
        books: List<DiscoveryBook>,
        library: List<LibraryBook>,
        wantToRead: List<DiscoveryBook>
    ): List<DiscoveryBook> {
        return books.filterNot { book ->
            wantToRead.any { sameWork(it, book) } || inLibrary(book, library)
        }
    }

    fun sameWork(a: DiscoveryBook, b: DiscoveryBook): Boolean {
        if (a.volumeId == b.volumeId) return true
        val isbnA = normalizeIsbn(a.isbn)
        val isbnB = normalizeIsbn(b.isbn)
        if (isbnA != null && isbnA == isbnB) return true
        return normalize(a.title) == normalize(b.title) && authorsCompatible(a.authors, b.authors)
    }

    fun inLibrary(book: DiscoveryBook, library: List<LibraryBook>): Boolean {
        val title = normalize(book.title)
        val isbn = normalizeIsbn(book.isbn)
        return library.any { lib ->
            lib.id == "gb_${book.volumeId}" ||
                lib.id == book.volumeId ||
                (isbn != null && normalizeIsbn(lib.id) == isbn) ||
                (normalize(lib.title) == title && authorsCompatible(book.authors, listOf(lib.author)))
        }
    }

    private suspend fun buildPool(
        library: List<LibraryBook>,
        wantToRead: List<DiscoveryBook>,
        day: String
    ): List<DiscoveryBook> {
        val specs = listOf(
            Query("subject:Fantasy", "relevance", 0),
            Query("subject:Romance", "relevance", 0),
            Query("subject:Adventure", "relevance", 0),
            Query("romantasy", "relevance", 0),
            Query("subject:Fantasy", "newest", 0),
            Query("subject:Adventure", "newest", 0),
            Query("subject:Fantasy", "relevance", 20)
        )
        val raw = ArrayList<DiscoveryBook>()
        specs.chunked(3).forEachIndexed { wave, chunk ->
            if (wave > 0) delay(280)
            val part = coroutineScope {
                chunk.map { spec ->
                    async {
                        runCatching {
                            GoogleBooks.volumes(spec.q, spec.orderBy, 20, spec.startIndex)
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            raw += part.flatten()
        }
        val deduped = LinkedHashMap<String, DiscoveryBook>()
        raw.forEach { book ->
            if (deduped.values.any { sameWork(it, book) }) return@forEach
            if (inLibrary(book, library)) return@forEach
            if (wantToRead.any { sameWork(it, book) }) return@forEach
            deduped[book.volumeId] = book
        }
        val rng = Random(day.hashCode())
        return pickDiverse(deduped.values.toList(), rng)
    }

    private fun pickDiverse(books: List<DiscoveryBook>, rng: Random): List<DiscoveryBook> {
        if (books.isEmpty()) return emptyList()
        val yearNow = Calendar.getInstance().get(Calendar.YEAR)
        val buckets = LinkedHashMap<Bucket, MutableList<DiscoveryBook>>()
        Bucket.values().forEach { buckets[it] = mutableListOf() }
        books.forEach { book ->
            classify(book, yearNow).forEach { buckets[it]?.add(book) }
        }
        buckets.forEach { (bucket, list) ->
            val ranked = list.distinctBy { it.volumeId }.sortedByDescending { quality(it, yearNow) }
            buckets[bucket] = lightShuffle(ranked, rng)
        }
        val usedIds = HashSet<String>()
        val authorCount = HashMap<String, Int>()
        val picked = ArrayList<DiscoveryBook>(LIMIT)
        fun tryAdd(book: DiscoveryBook): Boolean {
            if (book.volumeId in usedIds) return false
            if (picked.any { sameWork(it, book) }) return false
            val authorKey = authorKey(book)
            if (authorKey.isNotEmpty() && (authorCount[authorKey] ?: 0) >= 2) return false
            picked += book
            usedIds += book.volumeId
            if (authorKey.isNotEmpty()) authorCount[authorKey] = (authorCount[authorKey] ?: 0) + 1
            return true
        }
        val cursors = Bucket.values().associateWith { 0 }.toMutableMap()
        var added = true
        while (picked.size < LIMIT && added) {
            added = false
            for (bucket in Bucket.values()) {
                if (picked.size >= LIMIT) break
                val list = buckets[bucket] ?: continue
                var i = cursors[bucket] ?: 0
                while (i < list.size) {
                    val book = list[i++]
                    cursors[bucket] = i
                    if (tryAdd(book)) {
                        added = true
                        break
                    }
                }
            }
        }
        if (picked.size < LIMIT) {
            books.sortedByDescending { quality(it, yearNow) }.forEach { book ->
                if (picked.size >= LIMIT) return@forEach
                tryAdd(book)
            }
        }
        return picked
    }

    private fun classify(book: DiscoveryBook, yearNow: Int): Set<Bucket> {
        val blob = (book.categories + book.title)
            .joinToString(" ")
            .lowercase(Locale.US)
        val fantasy = blob.contains("fantasy")
        val romance = blob.contains("romance")
        val adventure = blob.contains("adventure")
        val romantasy = blob.contains("romantasy") || (fantasy && romance)
        val year = book.publishedDate.take(4).toIntOrNull() ?: 0
        val out = LinkedHashSet<Bucket>()
        if (romantasy) out += Bucket.ROMANTASY
        else if (fantasy) out += Bucket.FANTASY
        if (romance && !romantasy) out += Bucket.ROMANCE
        if (adventure) out += Bucket.ADVENTURE
        if (year >= yearNow - 1) out += Bucket.NEW
        if (book.ratingsCount >= 80 && book.averageRating >= 3.7f) out += Bucket.POPULAR
        if (out.isEmpty()) {
            when {
                fantasy -> out += Bucket.FANTASY
                romance -> out += Bucket.ROMANCE
                adventure -> out += Bucket.ADVENTURE
                else -> out += Bucket.POPULAR
            }
        }
        return out
    }

    private fun quality(book: DiscoveryBook, yearNow: Int): Double {
        val popular = book.averageRating.coerceIn(0f, 5f) * ln(1.0 + book.ratingsCount.coerceAtLeast(0))
        val year = book.publishedDate.take(4).toIntOrNull() ?: 0
        val recency = when {
            year >= yearNow - 1 -> 2.2
            year >= yearNow - 3 -> 1.2
            year >= yearNow - 8 -> 0.4
            else -> 0.0
        }
        return popular * 0.45 + recency
    }

    private fun lightShuffle(list: List<DiscoveryBook>, rng: Random): MutableList<DiscoveryBook> {
        val out = list.toMutableList()
        if (out.size < 2) return out
        val swaps = (out.size / 5).coerceIn(1, 4)
        repeat(swaps) {
            val i = rng.nextInt(out.size)
            val j = (i + rng.nextInt(1, 3)).coerceAtMost(out.lastIndex)
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }

    private fun authorKey(book: DiscoveryBook): String {
        val primary = book.authors.firstOrNull().orEmpty()
        val parts = normalize(primary).split(" ").filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts.first() + " " + parts.last() else parts.joinToString(" ")
    }

    fun authorsCompatible(a: List<String>, b: List<String>): Boolean {
        val left = a.map { normalize(it) }.filter { it.isNotEmpty() }
        val right = b.map { normalize(it) }.filter { it.isNotEmpty() }
        if (left.isEmpty() || right.isEmpty()) return false
        return left.any { la -> right.any { rb -> authorsCompatible(la, rb) } }
    }

    private fun authorsCompatible(a: String, b: String): Boolean {
        if (a == b) return true
        val pa = a.split(" ").filter { it.isNotEmpty() }
        val pb = b.split(" ").filter { it.isNotEmpty() }
        if (pa.size < 2 || pb.size < 2) return false
        val lastA = pa.last()
        val lastB = pb.last()
        if (lastA != lastB || lastA.length < 4) return false
        val firstA = pa.first()
        val firstB = pb.first()
        if (firstA == firstB) return true
        val initialA = firstA.length == 1
        val initialB = firstB.length == 1
        return (initialA || initialB) && firstA.first() == firstB.first()
    }

    fun normalizeIsbn(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        return digits.takeIf { it.length >= 10 }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun readUnlocked(context: Context): Cache? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val day = o.optString("day")
            if (day.isBlank()) return@runCatching null
            val arr = o.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<DiscoveryBook>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                parseBook(row)?.let { books += it }
            }
            Cache(day, books)
        }.getOrNull()
    }

    private fun writeUnlocked(context: Context, day: String, books: List<DiscoveryBook>) {
        val arr = JSONArray()
        books.forEach { arr.put(toJson(it)) }
        val o = JSONObject().put("day", day).put("books", arr)
        val dir = context.filesDir
        val target = File(dir, CACHE_FILE)
        val tmp = File(dir, "$CACHE_FILE.tmp")
        runCatching {
            tmp.writeText(o.toString())
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun toJson(book: DiscoveryBook): JSONObject {
        val authors = JSONArray()
        book.authors.forEach { authors.put(it) }
        val categories = JSONArray()
        book.categories.forEach { categories.put(it) }
        return JSONObject()
            .put("volumeId", book.volumeId)
            .put("title", book.title)
            .put("authors", authors)
            .put("description", book.description)
            .put("categories", categories)
            .put("coverUrl", book.coverUrl ?: "")
            .put("publishedDate", book.publishedDate)
            .put("averageRating", book.averageRating.toDouble())
            .put("ratingsCount", book.ratingsCount)
            .put("isbn", book.isbn ?: "")
            .put("infoLink", book.infoLink ?: "")
            .put("previewLink", book.previewLink ?: "")
            .put("canonicalLink", book.canonicalLink ?: "")
            .put("buyLink", book.buyLink ?: "")
            .put("publicDomain", book.publicDomain)
            .put("savedAt", book.savedAt)
    }

    fun parseBook(o: JSONObject): DiscoveryBook? {
        val id = o.optString("volumeId").trim()
        val title = o.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        return DiscoveryBook(
            volumeId = id,
            title = title,
            authors = stringList(o.optJSONArray("authors")),
            description = o.optString("description"),
            categories = stringList(o.optJSONArray("categories")),
            coverUrl = o.optString("coverUrl").ifBlank { null },
            publishedDate = o.optString("publishedDate"),
            averageRating = o.optDouble("averageRating", 0.0).toFloat(),
            ratingsCount = o.optInt("ratingsCount", 0),
            isbn = o.optString("isbn").ifBlank { null },
            infoLink = o.optString("infoLink").ifBlank { null },
            previewLink = o.optString("previewLink").ifBlank { null },
            canonicalLink = o.optString("canonicalLink").ifBlank { null },
            buyLink = o.optString("buyLink").ifBlank { null },
            publicDomain = o.optBoolean("publicDomain", false),
            savedAt = o.optLong("savedAt", 0L)
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

    fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class Query(val q: String, val orderBy: String, val startIndex: Int)
    private data class Cache(val day: String, val books: List<DiscoveryBook>)
    private enum class Bucket { ROMANTASY, FANTASY, ROMANCE, ADVENTURE, NEW, POPULAR }
}
