package com.lantern.library.studio

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal object ChapterDiscovery {
    private val NEXT = Regex(
        "^(next\\s*(chapter|page|ch)?|下一章|下一页|次へ|다음)$",
        RegexOption.IGNORE_CASE
    )
    private val PREV = Regex(
        "^(prev(ious)?\\s*(chapter|page|ch)?|上一章|上一页|前へ|이전)$",
        RegexOption.IGNORE_CASE
    )
    private val CH_NUM = Regex(
        "(?:chapter|ch|ep(?:isode)?|part)\\s*[:.\\-]?\\s*(\\d{1,4})",
        RegexOption.IGNORE_CASE
    )
    private val PATH_NUM = Regex("(?:chapter|ch|ep|episode|page|p)[_\\-]?(\\d{1,4})(?:\\D|$)", RegexOption.IGNORE_CASE)

    data class Result(
        val next: String?,
        val prev: String?,
        val toc: List<TocLink>,
        val unsure: List<WebLinkGuess>
    )

    data class TocLink(val url: String, val label: String, val number: Int?)

    fun inspect(html: String, pageUrl: String): Result {
        val start = pageUrl.toHttpUrlOrNull() ?: return Result(null, null, emptyList(), emptyList())
        val doc = Jsoup.parse(html, pageUrl)
        var next: String? = null
        var prev: String? = null
        val unsure = ArrayList<WebLinkGuess>()
        val tocish = ArrayList<TocLink>()
        var guessN = 0
        for (a in doc.select("a[href]")) {
            val href = absHttps(a, start) ?: continue
            if (samePage(href, pageUrl)) continue
            val host = href.toHttpUrlOrNull()?.host ?: continue
            if (!host.equals(start.host, true)) continue
            val label = a.text().replace(Regex("\\s+"), " ").trim().ifBlank {
                a.attr("title").trim()
            }.ifBlank { href.substringAfterLast('/') }
            val rel = a.attr("rel").lowercase()
            val blob = (a.className() + " " + a.id() + " " + rel + " " + label)
            val num = chapterNumber(label, href)
            when {
                rel.split(Regex("\\s+")).any { it == "next" } || NEXT.matches(label) ||
                    blob.contains("next-chap") || blob.contains("chapter-next") ->
                    if (next == null && relatedPath(start.encodedPath, href.toHttpUrlOrNull()?.encodedPath)) next = href
                rel.split(Regex("\\s+")).any { it == "prev" } || PREV.matches(label) ||
                    blob.contains("prev-chap") || blob.contains("chapter-prev") ->
                    if (prev == null && relatedPath(start.encodedPath, href.toHttpUrlOrNull()?.encodedPath)) prev = href
                looksLikeToc(a) && (num != null || CH_NUM.containsMatchIn(label)) ->
                    tocish += TocLink(href, label.take(80), num)
                num != null && relatedPath(start.encodedPath, href.toHttpUrlOrNull()?.encodedPath) ->
                    unsure += WebLinkGuess("g${guessN++}", href, label.take(80), "Possible next chapter found")
            }
        }
        val toc = collapseToc(tocish, pageUrl)
        val unsureClean = unsure
            .distinctBy { it.url }
            .filter { it.url != next && it.url != prev && toc.none { t -> t.url == it.url } }
            .take(8)
        return Result(next, prev, toc, unsureClean)
    }

    private fun looksLikeToc(a: Element): Boolean {
        var cur: Element? = a.parent()
        var hops = 0
        while (cur != null && hops < 6) {
            val blob = cur.tagName() + " " + cur.className() + " " + cur.id()
            if (Regex("toc|chapter-list|chapters|index-list|episode-list", RegexOption.IGNORE_CASE).containsMatchIn(blob)) {
                return true
            }
            cur = cur.parent()
            hops++
        }
        return false
    }

    private fun collapseToc(raw: List<TocLink>, startUrl: String): List<TocLink> {
        val uniq = LinkedHashMap<String, TocLink>()
        raw.forEach { uniq.putIfAbsent(it.url, it) }
        val list = uniq.values.toList()
        if (list.size < 3) return emptyList()
        val startPath = startUrl.toHttpUrlOrNull()?.encodedPath ?: return emptyList()
        val related = list.filter { relatedPath(startPath, it.url.toHttpUrlOrNull()?.encodedPath) }
        if (related.size < 3) return emptyList()
        return related.take(40)
    }

    fun chapterNumber(label: String, url: String): Int? {
        CH_NUM.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        PATH_NUM.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun relatedPath(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val da = a.trimEnd('/').substringBeforeLast('/')
        val db = b.trimEnd('/').substringBeforeLast('/')
        if (da.isEmpty() || db.isEmpty()) return false
        return da == db
    }

    private fun absHttps(a: Element, start: okhttp3.HttpUrl): String? {
        var href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.startsWith("http://")) href = "https://" + href.removePrefix("http://")
        if (!href.startsWith("https://")) return null
        val u = href.toHttpUrlOrNull() ?: return null
        if (u.host != start.host) return null
        return u.newBuilder().fragment(null).build().toString()
    }

    private fun samePage(a: String, b: String): Boolean {
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua.host == ub.host && ua.encodedPath.trimEnd('/') == ub.encodedPath.trimEnd('/')
    }

    private val JUNK_LABEL = Regex(
        "^(home|homepage|login|log\\s*in|sign\\s*in|sign\\s*up|register|comment(s)?|" +
            "leave a comment|about( us)?|contact|privacy(\\s*policy)?|terms(\\s*of\\s*service)?|" +
            "search|forum|discord|patreon|donate|shop|cart|next|previous|prev|older|newer|" +
            "tag(s)?|categor(y|ies)|rss|feed|share|facebook|twitter|instagram|random|" +
            "profile|settings|help|faq|sitemap|subscribe|cookie(s)?|menu|close|top|back)$",
        RegexOption.IGNORE_CASE
    )
    private val JUNK_PATH = Regex(
        "/(login|signin|signup|register|comment|comments|tag|tags|category|search|cart|account|privacy|terms|help|faq)(/|$)",
        RegexOption.IGNORE_CASE
    )
    private val BARE_NUM = Regex("^\\d{1,4}([.):]\\s*.+)?$")

    fun collection(html: String, pageUrl: String, pageTitle: String): List<WebPagePick> {
        val start = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val doc = Jsoup.parse(html, pageUrl)
        val found = LinkedHashMap<String, TocLink>()
        val byParent = HashMap<Element, ArrayList<TocLink>>()
        for (a in doc.select("a[href]")) {
            val href = absHttps(a, start) ?: continue
            if (samePage(href, pageUrl)) continue
            val path = href.toHttpUrlOrNull()?.encodedPath.orEmpty()
            if (JUNK_PATH.containsMatchIn(path)) continue
            val label = a.text().replace(Regex("\\s+"), " ").trim().ifBlank {
                a.attr("title").trim()
            }.ifBlank { href.substringAfterLast('/') }
            val compact = label.lowercase().trim()
            if (JUNK_LABEL.matches(compact)) continue
            val rel = a.attr("rel").lowercase()
            if (rel.split(Regex("\\s+")).any { it == "next" || it == "prev" }) continue
            if (NEXT.matches(label) || PREV.matches(label)) continue
            val num = chapterNumber(label, href) ?: BARE_NUM.find(label)?.value?.takeWhile { it.isDigit() }?.toIntOrNull()
            val toc = looksLikeToc(a)
            val related = relatedLoose(start.encodedPath, href.toHttpUrlOrNull()?.encodedPath)
            val parent = a.parent()
            if (parent != null) {
                val bucket = byParent.getOrPut(parent) { ArrayList() }
                bucket += TocLink(href, label.take(80), num)
            }
            if (toc || num != null || (related && (CH_NUM.containsMatchIn(label) || BARE_NUM.matches(label)))) {
                found.putIfAbsent(normUrl(href), TocLink(href, label.take(80), num))
            }
        }
        byParent.values.filter { it.size >= 3 }.forEach { group ->
            val chapterish = group.count { it.number != null || CH_NUM.containsMatchIn(it.label) || BARE_NUM.matches(it.label) }
            if (chapterish >= 3) {
                group.forEach { found.putIfAbsent(normUrl(it.url), it) }
            }
        }
        if (found.isEmpty()) return emptyList()
        val currentTitle = pageTitle.trim().ifBlank { "This page" }
        val current = WebPagePick("p0", canon(pageUrl), currentTitle, true)
        val rest = found.values
            .filter { !samePage(it.url, pageUrl) }
            .take(40)
            .mapIndexed { i, t ->
                WebPagePick("p${i + 1}", canon(t.url), t.label.ifBlank { "Page ${i + 2}" }, true)
            }
        return listOf(current) + rest
    }

    private fun relatedLoose(a: String?, b: String?): Boolean {
        if (relatedPath(a, b)) return true
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val sa = a.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val sb = b.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (sa.size >= 2 && sb.size >= 2 && sa[0] == sb[0] && sa[1] == sb[1]) return true
        val da = a.trimEnd('/')
        val db = b.trimEnd('/')
        return da.startsWith("$db/") || db.startsWith("$da/")
    }

    private fun normUrl(url: String): String = url.substringBefore('#').trimEnd('/').lowercase()

    private fun canon(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url.substringBefore('#')
        return u.newBuilder().fragment(null).build().toString()
    }
}
