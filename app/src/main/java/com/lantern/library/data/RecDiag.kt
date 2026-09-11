package com.lantern.library.data

import android.util.Log
import com.lantern.library.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Temporary debug-only For You pipeline trace. Never logs the API key or URLs.
 * Does not change ranking, queries, or cache behavior.
 */
object RecDiag {
    private const val TAG = "LoreForYou"

    @Volatile var keyConfigured: Boolean = false
    @Volatile var online: Boolean = false
    @Volatile var cacheHit: Boolean = false
    @Volatile var cacheSize: Int = 0
    @Volatile var requestStarted: Boolean = false
    @Volatile var rawCount: Int = 0
    @Volatile var filteredPoolCount: Int = 0
    @Volatile var rankedCount: Int = 0
    @Volatile var cachedWriteSize: Int = -1
    @Volatile var storeCount: Int = 0
    @Volatile var skip: String? = null
    private val httpCodes = CopyOnWriteArrayList<Int>()
    private val httpItems = CopyOnWriteArrayList<Int>()

    fun reset() {
        keyConfigured = false
        online = false
        cacheHit = false
        cacheSize = 0
        requestStarted = false
        rawCount = 0
        filteredPoolCount = 0
        rankedCount = 0
        cachedWriteSize = -1
        storeCount = 0
        skip = null
        httpCodes.clear()
        httpItems.clear()
    }

    fun http(code: Int, itemCount: Int) {
        httpCodes += code
        httpItems += itemCount
        log("http=$code items=$itemCount")
    }

    fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, redact(msg))
    }

    /** Never emit a Google API key, even if a caller accidentally concatenates one. */
    private fun redact(msg: String): String =
        msg.replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]")
            .replace(Regex("(?i)googleapis\\.com[^\\s]*"), "googleapis.com/[redacted]")

    fun cause(): String {
        if (!keyConfigured) return "missing key"
        if (cacheHit) return "24h cache n=$cacheSize"
        if (skip == "offline") return "network failure (offline)"
        if (!requestStarted && skip != null) return skip ?: "skipped"
        val codes = httpCodes.toList()
        if (codes.isEmpty() && requestStarted) return "request started, no HTTP recorded"
        if (codes.isEmpty()) return "volumes() never called"
        if (codes.any { it == -1 }) return "network failure"
        if (codes.any { it == 403 }) return "HTTP 403"
        if (codes.any { it == 400 }) return "HTTP 400"
        if (codes.any { it == 429 }) return "HTTP 429"
        val other = codes.firstOrNull { it != 200 }
        if (other != null) return "HTTP $other"
        val items = httpItems.toList().sum()
        if (items <= 0) return "HTTP 200 with zero items"
        if (rawCount > 0 && filteredPoolCount == 0) return "HTTP 200 with items but filtering removes everything"
        if (rankedCount > 0 && storeCount == 0) return "recommendations generated but not reaching Library"
        if (storeCount > 0) return "ok store=$storeCount"
        if (rankedCount == 0 && rawCount > 0) return "ranked empty after pool"
        return "empty after HTTP 200 items=$items raw=$rawCount"
    }

    fun summary(): String {
        val codes = httpCodes.toList().ifEmpty { listOf() }
        val codePart = if (codes.isEmpty()) "http=none" else "http=${codes.joinToString(",")}"
        return redact(
            "For You: ${cause()} | key=${if (keyConfigured) "yes" else "NO"} " +
                "online=$online cache=${if (cacheHit) "hit/$cacheSize" else "miss"} " +
                "req=${if (requestStarted) "yes" else "no"} $codePart " +
                "raw=$rawCount filt=$filteredPoolCount rank=$rankedCount store=$storeCount"
        )
    }
}
