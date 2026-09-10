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
import java.util.Calendar
import java.util.Locale
import kotlin.math.ln
import kotlin.random.Random

object Recommendations {
    private const val CACHE_FILE = "recommendations.json"
    private const val LIMIT = 12
    /** For You set lifetime. Change this to retune refresh cadence. */
    const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val cacheLock = Any()
    private val SUBJECT_STOP = setOf(
        "fiction", "general", "literature", "imported", "library", "books",
        "unclassified", "miscellaneous"
    )
    private val GENERIC_QUERIES = listOf(
        Query("subject:Fantasy", "relevance", 0),
        Query("subject:Romance", "relevance", 0),
        Query("subject:Adventure", "relevance", 0),
        Query("romantasy", "relevance", 0),
        Query("subject:Fantasy", "newest", 0),
        Query("subject:Adventure", "newest", 0),
        Query("subject:Fantasy", "relevance", 20)
    )

    fun cached(context: Context): List<DiscoveryBook>? =
        synchronized(cacheLock) { readUnlocked(context)?.books }

    fun excludeFromCache(context: Context, drop: (DiscoveryBook) -> Boolean) {
        synchronized(cacheLock) {
            val cache = readUnlocked(context) ?: return
            val kept = cache.books.filterNot(drop)
            if (kept.size == cache.books.size) return
            writeUnlocked(context, cache.lastRefreshAt, kept)
        }
    }

    suspend fun daily(
        context: Context,
        library: () -> List<LibraryBook>,
        wantToRead: () -> List<DiscoveryBook>
    ): List<DiscoveryBook> {
        val cached = synchronized(cacheLock) { readUnlocked(context) }
        if (cached != null && !isExpired(cached.lastRefreshAt)) {
            return filterExcluded(cached.books, library(), wantToRead())
        }
        if (!GoogleBooks.isConfigured() || !isOnline(context)) {
            val previous = synchronized(cacheLock) { readUnlocked(context)?.books.orEmpty() }
            return filterExcluded(previous, library(), wantToRead())
        }
        val avoidIds = cached?.books?.map { it.volumeId }?.toSet().orEmpty()
        val built = withContext(Dispatchers.IO) {
            runCatching { buildPool(library(), wantToRead(), avoidIds) }.getOrNull()
        }
        return synchronized(cacheLock) {
            val existing = readUnlocked(context)
            val lib = library()
            val want = wantToRead()
            if (existing != null && !isExpired(existing.lastRefreshAt) &&
                existing.lastRefreshAt != cached?.lastRefreshAt
            ) {
                val merged = filterExcluded(existing.books, lib, want)
                if (merged.size != existing.books.size) {
                    writeUnlocked(context, existing.lastRefreshAt, merged)
                }
                merged
            } else if (built.isNullOrEmpty()) {
                filterExcluded(existing?.books.orEmpty(), lib, want)
            } else {
                val filtered = filterExcluded(built, lib, want)
                writeUnlocked(context, System.currentTimeMillis(), filtered)
                filtered
            }
        }
    }

    private fun isExpired(lastRefreshAt: Long): Boolean =
        lastRefreshAt <= 0L ||
            System.currentTimeMillis() - lastRefreshAt >= REFRESH_INTERVAL_MS

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
        avoidIds: Set<String>
    ): List<DiscoveryBook> {
        val profile = buildProfile(library, wantToRead)
        val specs = queriesFromProfile(profile)
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
        return rankByTaste(deduped.values.toList(), profile, avoidIds)
    }

    private data class TasteAuthor(val name: String, val weight: Int, val recency: Long)
    private data class TasteSubject(val token: String, val weight: Int)
    private data class TasteProfile(val authors: List<TasteAuthor>, val subjects: List<TasteSubject>) {
        val empty: Boolean get() = authors.isEmpty() && subjects.isEmpty()
    }

    private fun buildProfile(library: List<LibraryBook>, wantToRead: List<DiscoveryBook>): TasteProfile {
        val authors = HashMap<String, TasteAuthor>()
        val subjects = HashMap<String, Int>()
        fun addAuthor(raw: String, weight: Int, recency: Long) {
            val name = raw.trim()
            if (!usableAuthor(name)) return
            val key = normalize(name)
            if (key.length < 3) return
            val prev = authors[key]
            if (prev == null) {
                authors[key] = TasteAuthor(name, weight, recency)
            } else {
                authors[key] = TasteAuthor(prev.name, prev.weight + weight, maxOf(prev.recency, recency))
            }
        }
        fun addSubject(raw: String, weight: Int) {
            subjectTokens(raw).forEach { token ->
                subjects[token] = (subjects[token] ?: 0) + weight
            }
        }
        library.forEach { book ->
            val weight = when (book.shelf) {
                Shelf.FINISHED -> 3
                Shelf.CURRENT -> 2
                else -> 1
            }
            addAuthor(book.author, weight, book.lastReadAt)
            addSubject(book.category, weight)
        }
        wantToRead.forEach { book ->
            val recency = if (book.savedAt > 0L) book.savedAt else 0L
            book.authors.forEach { addAuthor(it, 2, recency) }
            book.categories.forEach { addSubject(it, 2) }
        }
        val authorList = authors.values
            .sortedWith(compareByDescending<TasteAuthor> { it.weight }.thenByDescending { it.recency })
        val subjectList = subjects.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { TasteSubject(it.key, it.value) }
        return TasteProfile(authorList, subjectList)
    }

    private fun usableAuthor(name: String): Boolean {
        val n = normalize(name)
        return n.isNotEmpty() && n != "imported" && n != "unknown" && n != "unknown author" && n != "anonymous"
    }

    private fun subjectTokens(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex("[,/&;|]+|(?:\\s+--+\\s+)"))
            .map { normalize(it) }
            .flatMap { part -> part.split(" ").filter { it.isNotEmpty() }.let { tokens ->
                val kept = tokens.filter { it !in SUBJECT_STOP && it.length >= 4 }
                if (kept.isEmpty()) emptyList() else listOf(kept.joinToString(" "))
            } }
            .distinct()
    }

    private fun queriesFromProfile(profile: TasteProfile): List<Query> {
        if (profile.empty) return GENERIC_QUERIES
        val out = ArrayList<Query>(7)
        val seen = HashSet<String>()
        fun add(q: String, orderBy: String, start: Int = 0) {
            if (out.size >= 7) return
            val id = "$q|$orderBy|$start"
            if (!seen.add(id)) return
            out += Query(q, orderBy, start)
        }
        profile.authors.take(3).forEach { author ->
            val safe = author.name.replace("\"", "").trim()
            if (safe.length >= 3) add("inauthor:\"$safe\"", "relevance")
        }
        profile.subjects.take(3).forEach { subject ->
            val token = subject.token
            val q = if (token.contains(" ")) "subject:\"$token\"" else "subject:$token"
            add(q, "relevance")
        }
        GENERIC_QUERIES.forEach { add(it.q, it.orderBy, it.startIndex) }
        return out
    }

    private fun rankByTaste(
        books: List<DiscoveryBook>,
        profile: TasteProfile,
        avoidIds: Set<String>
    ): List<DiscoveryBook> {
        if (books.isEmpty()) return emptyList()
        val yearNow = Calendar.getInstance().get(Calendar.YEAR)
        val ranked = books.sortedWith(
            compareBy<DiscoveryBook> { if (it.volumeId in avoidIds) 1 else 0 }
                .thenByDescending { tasteScore(it, profile) }
                .thenByDescending { quality(it, yearNow) }
        )
        val usedIds = HashSet<String>()
        val authorCount = HashMap<String, Int>()
        val picked = ArrayList<DiscoveryBook>(LIMIT)
        ranked.forEach { book ->
            if (picked.size >= LIMIT) return@forEach
            if (book.volumeId in usedIds) return@forEach
            if (picked.any { sameWork(it, book) }) return@forEach
            val key = authorKey(book)
            if (key.isNotEmpty() && (authorCount[key] ?: 0) >= 2) return@forEach
            picked += book
            usedIds += book.volumeId
            if (key.isNotEmpty()) authorCount[key] = (authorCount[key] ?: 0) + 1
        }
        return picked
    }

    private fun tasteScore(book: DiscoveryBook, profile: TasteProfile): Double {
        if (profile.empty) return 0.0
        var score = 0.0
        val authorHit = profile.authors.filter { authorsCompatible(book.authors, listOf(it.name)) }
        score += authorHit.maxOfOrNull { it.weight.toDouble() } ?: 0.0
        val bookTokens = book.categories.flatMap { subjectTokens(it) }.toSet()
        profile.subjects.forEach { sub ->
            val hit = sub.token in bookTokens || bookTokens.any { token ->
                token == sub.token || token.contains(sub.token) || sub.token.contains(token)
            }
            if (hit) score += sub.weight * 0.5
        }
        return score
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
            val lastRefreshAt = o.optLong("lastRefreshAt", 0L)
            if (lastRefreshAt <= 0L) return@runCatching null
            val arr = o.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<DiscoveryBook>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                parseBook(row)?.let { books += it }
            }
            Cache(lastRefreshAt, books)
        }.getOrNull()
    }

    private fun writeUnlocked(context: Context, lastRefreshAt: Long, books: List<DiscoveryBook>) {
        val arr = JSONArray()
        books.forEach { arr.put(toJson(it)) }
        val o = JSONObject().put("lastRefreshAt", lastRefreshAt).put("books", arr)
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
    private data class Cache(val lastRefreshAt: Long, val books: List<DiscoveryBook>)
    private enum class Bucket { ROMANTASY, FANTASY, ROMANCE, ADVENTURE, NEW, POPULAR }
}
