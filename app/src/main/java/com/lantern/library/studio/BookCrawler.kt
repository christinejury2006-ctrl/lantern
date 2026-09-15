package com.lantern.library.studio

import java.io.File

internal object BookCrawler {
    const val MAX_PAGES = 20

    fun crawl(startRaw: String, cacheDir: File, onProgress: (String) -> Unit): WebDraft {
        WebFetch.clearWebDir(cacheDir)
        val dir = WebFetch.webDir(cacheDir)
        onProgress("Reading first page…")
        val first = WebFetch.page(startRaw)
        val discovery = ChapterDiscovery.inspect(first.html, first.url)
        val firstParse = PageAnalyzer.analyze(first.html, first.url)
        val chapters = ArrayList<WebChapter>()
        val visited = HashSet<String>()

        if (discovery.toc.size >= 3) {
            discovery.toc.take(MAX_PAGES).forEachIndexed { i, toc ->
                val key = norm(toc.url)
                if (!visited.add(key)) return@forEachIndexed
                onProgress("Reading ${i + 1} of ${discovery.toc.size.coerceAtMost(MAX_PAGES)}…")
                val page = if (same(toc.url, first.url)) first else runCatching { WebFetch.page(toc.url) }.getOrNull()
                if (page == null) return@forEachIndexed
                val parse = if (same(toc.url, first.url)) firstParse else PageAnalyzer.analyze(page.html, page.url)
                val label = toc.label.ifBlank { parse.title }
                chapters += toChapter(parse, dir, chapters.size, label, toc.number)
                if (!same(toc.url, first.url)) Thread.sleep(150)
            }
        } else {
            var page = first
            var html = first.html
            var parse = firstParse
            while (chapters.size < MAX_PAGES) {
                val key = norm(page.url)
                if (!visited.add(key)) break
                onProgress("Reading page ${chapters.size + 1}…")
                chapters += toChapter(parse, dir, chapters.size, parse.title, ChapterDiscovery.chapterNumber(parse.title, parse.url))
                val nxt = ChapterDiscovery.inspect(html, page.url).next ?: break
                if (visited.contains(norm(nxt))) break
                val nextPage = runCatching { WebFetch.page(nxt) }.getOrNull() ?: break
                page = nextPage
                html = nextPage.html
                parse = PageAnalyzer.analyze(html, nextPage.url)
                Thread.sleep(150)
            }
        }
        if (chapters.isEmpty()) {
            chapters += toChapter(
                firstParse, dir, 0, firstParse.title,
                ChapterDiscovery.chapterNumber(firstParse.title, firstParse.url)
            )
        }
        val bookKind = if (chapters.count { it.kind == PageKind.Images } * 2 > chapters.size) {
            PageKind.Images
        } else PageKind.Text
        return WebDraft(
            phase = WebPhase.Ready,
            url = first.url,
            title = stripChapter(firstParse.title).ifBlank { firstParse.title },
            kind = bookKind,
            chapters = chapters,
            possible = discovery.unsure.filter { g -> chapters.none { same(it.url, g.url) } },
            openChapterId = chapters.firstOrNull()?.id
        )
    }

    fun fetchOne(url: String, cacheDir: File, index: Int): WebChapter? {
        val page = runCatching { WebFetch.page(url) }.getOrNull() ?: return null
        val parse = PageAnalyzer.analyze(page.html, page.url)
        return toChapter(
            parse, WebFetch.webDir(cacheDir), index, parse.title,
            ChapterDiscovery.chapterNumber(parse.title, parse.url)
        )
    }

    private fun toChapter(
        parse: PageParse,
        dir: File,
        index: Int,
        label: String,
        number: Int?
    ): WebChapter {
        val id = "ch$index"
        val title = label.ifBlank {
            number?.let { "Chapter $it" } ?: parse.url.substringAfterLast('/').ifBlank { "Page ${index + 1}" }
        }
        val cands = attachImages(parse.candidates, dir, index).map { it.copy(id = "${id}_${it.id}") }
        return WebChapter(id, parse.url, title, index, number, parse.kind, cands)
    }

    private fun attachImages(candidates: List<WebCandidate>, dir: File, chapterIndex: Int): List<WebCandidate> {
        val preview = candidates.filter {
            it.type == WebBlockType.Image && it.verdict != WebVerdict.Drop && !it.imageUrl.isNullOrBlank()
        }.take(8)
        return candidates.map { c ->
            val hit = preview.indexOfFirst { it.id == c.id }
            if (hit < 0) c else {
                val dest = File(dir, "c${chapterIndex}_$hit.bin")
                val ok = WebFetch.image(c.imageUrl!!, dest)
                if (ok) c.copy(localPath = dest.absolutePath) else c
            }
        }
    }

    private fun stripChapter(title: String): String {
        val t = title.replace(Regex("(?i)\\s*[-|:]+\\s*(chapter|ch|episode|ep)\\s*\\d+.*$"), "").trim()
        return t.ifBlank { title }
    }

    private fun norm(url: String): String = url.substringBefore('#').trimEnd('/').lowercase()

    private fun same(a: String, b: String): Boolean = norm(a) == norm(b)
}
