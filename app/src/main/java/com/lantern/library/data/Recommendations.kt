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
    private const val SCHEMA = 2
    private const val AVOID_LIMIT = 48
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

    fun cached(context: Context, interests: List<String>): List<DiscoveryBook>? =
        synchronized(cacheLock) {
            val cache = readUnlocked(context, InterestCatalog.key(interests)) ?: return@synchronized null
            if (cache.books.isEmpty() || isExpired(cache.lastRefreshAt)) null else cache.books
        }

    fun excludeFromCache(context: Context, drop: (DiscoveryBook) -> Boolean) {
        synchronized(cacheLock) {
            val cache = readUnlocked(context, null) ?: return
            val kept = cache.books.filterNot(drop)
            if (kept.size == cache.books.size) return
            writeUnlocked(
                context, cache.lastRefreshAt, kept, cache.interestsKey,
                cache.refreshGen, cache.recentIds
            )
        }
    }

    fun clearCache(context: Context) {
        synchronized(cacheLock) {
            runCatching { File(context.filesDir, CACHE_FILE).delete() }
            runCatching { File(context.filesDir, "$CACHE_FILE.tmp").delete() }
        }
    }

    suspend fun daily(
        context: Context,
        interests: List<String>,
        forceRefresh: Boolean = false
    ): List<DiscoveryBook> {
        RecDiag.reset()
        RecDiag.keyConfigured = GoogleBooks.isConfigured()
        RecDiag.online = probeNetwork(context)
        val interestKey = InterestCatalog.key(interests)
        RecDiag.log("daily start key=${RecDiag.keyConfigured} online=${RecDiag.online} force=$forceRefresh interests=${interests.size} ids=${interestKey}")
        if (interestKey.isEmpty()) {
            RecDiag.skip = "no interests"
            RecDiag.log("daily skip no interests")
            clearCache(context)
            return emptyList()
        }
        val cached = synchronized(cacheLock) { readUnlocked(context, interestKey) }
        if (!forceRefresh && cached != null && cached.books.isNotEmpty() && !isExpired(cached.lastRefreshAt)) {
            RecDiag.cacheHit = true
            RecDiag.cacheSize = cached.books.size
            RecDiag.log("cache hit n=${cached.books.size}")
            return cached.books
        }
        if (!GoogleBooks.isConfigured()) {
            RecDiag.skip = "missing key"
            RecDiag.log("daily skip missing key")
            if (forceRefresh) {
                persistHistory(context, interestKey, cached, emptyList(), cached?.refreshGen ?: 0)
                return emptyList()
            }
            return if (cached != null && cached.books.isNotEmpty() && !isExpired(cached.lastRefreshAt)) cached.books else emptyList()
        }
        val avoidIds = LinkedHashSet<String>()
        if (forceRefresh) {
            cached?.recentIds?.let { avoidIds += it }
            cached?.books?.forEach { avoidIds += it.volumeId }
        }
        while (avoidIds.size > AVOID_LIMIT) avoidIds.remove(avoidIds.first())
        RecDiag.log("avoidIds=${avoidIds.joinToString(",")}")
        val hadPool = cached != null && cached.books.isNotEmpty()
        val nextGen = if (forceRefresh && hadPool) cached!!.refreshGen + 1 else (cached?.refreshGen ?: 0)
        val startIndex = if (forceRefresh && hadPool) (nextGen % 3) * 20 else 0
        val mixNewest = forceRefresh && hadPool && (nextGen % 2 == 1)
        RecDiag.log("buildPool start interests=$interestKey gen=$nextGen start=$startIndex mixNewest=$mixNewest")
        val built = withContext(Dispatchers.IO) {
            try {
                buildPool(interests, avoidIds, startIndex, mixNewest)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                RecDiag.skip = e.javaClass.simpleName
                RecDiag.log("buildPool failed ${e.javaClass.simpleName}")
                null
            }
        }
        if (built.isNullOrEmpty()) {
            RecDiag.log("built empty, not restoring pool")
            persistHistory(context, interestKey, cached, emptyList(), nextGen)
            return emptyList()
        }
        RecDiag.log("finalIds=${built.map { it.volumeId }.joinToString(",")}")
        synchronized(cacheLock) {
            RecDiag.cachedWriteSize = built.size
            RecDiag.log("cache write n=${built.size}")
            val recent = (built.map { it.volumeId } + (cached?.recentIds ?: emptyList()))
                .distinct()
                .take(AVOID_LIMIT)
            writeUnlocked(context, System.currentTimeMillis(), built, interestKey, nextGen, recent)
        }
        return built
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
        interests: List<String>,
        avoidIds: Set<String>,
        startIndex: Int,
        mixNewest: Boolean
    ): List<DiscoveryBook> {
        val specs = queriesFromInterests(interests, startIndex, mixNewest)
        val raw = ArrayList<DiscoveryBook>()
        RecDiag.log("buildPool queries=${specs.size}")
        if (specs.isEmpty()) return emptyList()
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
        RecDiag.rawCount = raw.size
        RecDiag.log("rawIds=${raw.map { it.volumeId }.distinct().take(40).joinToString(",")}")
        val deduped = LinkedHashMap<String, DiscoveryBook>()
        raw.forEach { book ->
            if (deduped.values.any { sameWork(it, book) }) return@forEach
            deduped[book.volumeId] = book
        }
        RecDiag.filteredPoolCount = deduped.size
        RecDiag.log("filtIds=${deduped.keys.take(40).joinToString(",")}")
        val ranked = rankByInterests(deduped.values.toList(), interests, avoidIds)
        RecDiag.rankedCount = ranked.size
        RecDiag.log("pool raw=${raw.size} filt=${deduped.size} rank=${ranked.size}")
        return ranked
    }

    private fun queriesFromInterests(
        interests: List<String>,
        startIndex: Int,
        mixNewest: Boolean
    ): List<Query> {
        val out = ArrayList<Query>(8)
        val seen = HashSet<String>()
        fun add(q: String, orderBy: String, start: Int) {
            if (out.size >= 8) return
            if (q.isBlank()) return
            val id = "$q|$orderBy|$start"
            if (!seen.add(id)) return
            RecDiag.log("query q=$q order=$orderBy start=$start")
            out += Query(q, orderBy, start)
        }
        val qs = ArrayList<String>()
        interests.forEach { id ->
            InterestCatalog.byId(id)?.queries?.forEach { qs += it }
        }
        if (!mixNewest) {
            qs.forEach { add(it, "relevance", startIndex) }
        } else {
            qs.forEachIndexed { i, q ->
                if (i % 2 == 0) add(q, "newest", 0) else add(q, "relevance", startIndex)
            }
            qs.forEach { add(it, "relevance", startIndex) }
        }
        return out
    }

    private fun rankByInterests(
        books: List<DiscoveryBook>,
        interests: List<String>,
        avoidIds: Set<String>
    ): List<DiscoveryBook> {
        if (books.isEmpty()) return emptyList()
        val yearNow = Calendar.getInstance().get(Calendar.YEAR)
        val phrases = interestPhrases(interests)
        data class Scored(
            val book: DiscoveryBook,
            val genre: Int,
            val quality: Double,
            val year: Int,
            val popular: Double
        )
        val scored = books.map { book ->
            val year = book.publishedDate.take(4).toIntOrNull() ?: 0
            val popular = book.averageRating.coerceIn(0f, 5f) * ln(1.0 + book.ratingsCount.coerceAtLeast(0))
            Scored(book, genreStrength(book, phrases), quality(book, yearNow), year, popular)
        }
        val byGenre = compareByDescending<Scored> { it.genre }
            .thenByDescending { it.quality }
            .thenBy { it.book.volumeId }
        val eligible = scored.filter { it.genre > 0 }
        val corePool = eligible.filter { it.genre >= 2 }.sortedWith(byGenre)
        val popularPool = eligible.filter { row ->
            row.genre >= 2 && (
                (row.book.ratingsCount >= 80 && row.book.averageRating >= 3.7f) ||
                    row.popular >= 8.0
                )
        }.sortedWith(
            compareByDescending<Scored> { it.popular }
                .thenByDescending { it.genre }
                .thenBy { it.book.volumeId }
        )
        val newPool = eligible.filter { it.genre >= 2 && it.year >= yearNow - 3 }
            .sortedWith(
                compareByDescending<Scored> { it.year }
                    .thenByDescending { it.genre }
                    .thenByDescending { it.quality }
                    .thenBy { it.book.volumeId }
            )
        val popularTop = popularPool.take(8).map { it.book.volumeId }.toSet()
        val newTop = newPool.take(8).map { it.book.volumeId }.toSet()
        val discoveryPool = eligible.filter {
            it.book.volumeId !in popularTop && it.book.volumeId !in newTop
        }.sortedWith(
            compareByDescending<Scored> { it.genre }
                .thenBy { it.popular }
                .thenBy { it.book.volumeId }
        )
        val overflow = eligible.sortedWith(byGenre)
        val usedIds = HashSet<String>()
        val authorCount = HashMap<String, Int>()
        val picked = ArrayList<DiscoveryBook>(LIMIT)
        fun tryAdd(row: Scored, skipAvoid: Boolean): Boolean {
            if (picked.size >= LIMIT) return false
            val book = row.book
            if (skipAvoid && book.volumeId in avoidIds) return false
            if (book.volumeId in usedIds) return false
            if (picked.any { sameWork(it, book) }) return false
            val key = authorKey(book)
            if (key.isNotEmpty() && (authorCount[key] ?: 0) >= 2) return false
            picked += book
            usedIds += book.volumeId
            if (key.isNotEmpty()) authorCount[key] = (authorCount[key] ?: 0) + 1
            return true
        }
        fun takeFrom(list: List<Scored>, quota: Int, skipAvoid: Boolean): Int {
            var n = 0
            for (row in list) {
                if (n >= quota || picked.size >= LIMIT) break
                if (tryAdd(row, skipAvoid)) n++
            }
            return n
        }
        val coreN = takeFrom(corePool, 4, true)
        val popN = takeFrom(popularPool, 3, true)
        val newN = takeFrom(newPool, 3, true)
        val discN = takeFrom(discoveryPool, 2, true)
        val overN = takeFrom(overflow, LIMIT - picked.size, true)
        if (picked.size < LIMIT) takeFrom(overflow, LIMIT - picked.size, false)
        if (picked.size < LIMIT) takeFrom(scored.sortedWith(byGenre), LIMIT - picked.size, false)
        RecDiag.log("buckets core=$coreN pop=$popN new=$newN disc=$discN overflow=$overN")
        return picked
    }

    private data class InterestPhrase(val phrase: String, val romantasy: Boolean)

    private fun interestPhrases(interests: List<String>): List<InterestPhrase> {
        val out = ArrayList<InterestPhrase>()
        interests.forEach { id ->
            val spec = InterestCatalog.byId(id) ?: return@forEach
            val romantasy = id == "romantasy"
            fun add(raw: String) {
                val n = normalize(raw)
                if (n.length < 3 || n in SUBJECT_STOP) return
                if (out.none { it.phrase == n && it.romantasy == romantasy }) {
                    out += InterestPhrase(n, romantasy)
                }
            }
            add(spec.label)
            if (romantasy) return@forEach
            spec.queries.forEach { q ->
                Regex("\"([^\"]+)\"").findAll(q).forEach { add(it.groupValues[1]) }
                Regex("(?i)subject:\"([^\"]+)\"").findAll(q).forEach { add(it.groupValues[1]) }
                Regex("(?i)subject:([^\\s\"]+)").findAll(q).forEach { add(it.groupValues[1]) }
                val bare = q.replace("\"", " ").replace(Regex("(?i)subject:"), " ")
                add(bare)
            }
        }
        return out
    }

    private fun hasPhrase(haystack: String, phrase: String): Boolean {
        if (phrase.isEmpty() || haystack.isEmpty()) return false
        return " $haystack ".contains(" $phrase ")
    }

    private fun genreStrength(book: DiscoveryBook, phrases: List<InterestPhrase>): Int {
        if (phrases.isEmpty()) return 0
        val cat = normalize(book.categories.joinToString(" "))
        val title = normalize(book.title)
        val desc = normalize(book.description.take(1200))
        var best = 0
        phrases.forEach { item ->
            var s = 0
            if (hasPhrase(cat, item.phrase)) s = 3
            else if (hasPhrase(title, item.phrase)) s = 2
            else if (hasPhrase(desc, item.phrase)) s = 1
            if (item.romantasy) {
                val fan = hasPhrase(cat, "fantasy") || hasPhrase(title, "fantasy")
                val rom = hasPhrase(cat, "romance") || hasPhrase(title, "romance")
                if (fan && rom) s = maxOf(s, 3)
            }
            if (s > best) best = s
        }
        return best
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

    private fun probeNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            RecDiag.log("network cm=null")
            return false
        }
        val network = cm.activeNetwork
        if (network == null) {
            RecDiag.log("network active=null")
            return false
        }
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null) {
            RecDiag.log("network caps=null")
            return false
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        RecDiag.log("network internet=$internet validated=$validated")
        return internet || validated
    }

    private fun persistHistory(
        context: Context,
        interestKey: String,
        cached: Cache?,
        books: List<DiscoveryBook>,
        refreshGen: Int
    ) {
        synchronized(cacheLock) {
            writeUnlocked(
                context,
                0L,
                books,
                interestKey,
                refreshGen,
                (cached?.recentIds ?: emptyList()).take(AVOID_LIMIT)
            )
        }
    }

    private fun readUnlocked(context: Context, expectedKey: String?): Cache? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            if (o.optInt("schema", 0) != SCHEMA) return@runCatching null
            val storedKey = o.optString("interestsKey", "")
            if (expectedKey != null && storedKey != expectedKey) return@runCatching null
            val lastRefreshAt = o.optLong("lastRefreshAt", 0L)
            val arr = o.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<DiscoveryBook>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                parseBook(row)?.let { books += it }
            }
            val recent = ArrayList<String>()
            val recentArr = o.optJSONArray("recentIds")
            if (recentArr != null) {
                for (i in 0 until recentArr.length()) {
                    val id = recentArr.optString(i).trim()
                    if (id.isNotEmpty()) recent += id
                }
            }
            Cache(lastRefreshAt, books, storedKey, o.optInt("refreshGen", 0), recent)
        }.getOrNull()
    }

    private fun writeUnlocked(
        context: Context,
        lastRefreshAt: Long,
        books: List<DiscoveryBook>,
        interestsKey: String,
        refreshGen: Int,
        recentIds: List<String>
    ) {
        val arr = JSONArray()
        books.forEach { arr.put(toJson(it)) }
        val recent = JSONArray()
        recentIds.forEach { recent.put(it) }
        val o = JSONObject()
            .put("schema", SCHEMA)
            .put("interestsKey", interestsKey)
            .put("lastRefreshAt", lastRefreshAt)
            .put("refreshGen", refreshGen)
            .put("recentIds", recent)
            .put("books", arr)
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
            .put("coverUrls", JSONArray().also { arr ->
                val urls = book.coverUrls.ifEmpty { listOfNotNull(book.coverUrl) }
                urls.forEach { arr.put(it) }
            })
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
            coverUrl = o.optString("coverUrl").ifBlank { null }.let { primary ->
                val extras = stringList(o.optJSONArray("coverUrls"))
                primary ?: extras.firstOrNull()
            },
            coverUrls = stringList(o.optJSONArray("coverUrls")).ifEmpty {
                listOfNotNull(o.optString("coverUrl").ifBlank { null })
            },
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
    private data class Cache(
        val lastRefreshAt: Long,
        val books: List<DiscoveryBook>,
        val interestsKey: String,
        val refreshGen: Int = 0,
        val recentIds: List<String> = emptyList()
    )
    private enum class Bucket { ROMANTASY, FANTASY, ROMANCE, ADVENTURE, NEW, POPULAR }
}
