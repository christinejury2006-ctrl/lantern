package com.lantern.library.studio

import java.io.File

internal object BookCrawler {
    const val MAX_PAGES = 20

    fun crawl(
        startRaw: String,
        cacheDir: File,
        cancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit
    ): WebDraft {
        fun alive() {
            if (cancelled()) error("cancelled")
        }
        WebFetch.clearWebDir(cacheDir)
        val dir = WebFetch.webDir(cacheDir)
        onProgress("Fetching page")
        alive()
        val first = WebFetch.page(startRaw)
        alive()
        onProgress("Finding chapters")
        val discovery = ChapterDiscovery.inspect(first.html, first.url)
        alive()
        onProgress("Analysing content")
        val firstParse = PageAnalyzer.analyze(first.html, first.url)
        val chapters = ArrayList<WebChapter>()
        val visited = HashSet<String>()

        if (discovery.toc.size >= 3) {
            val total = discovery.toc.size.coerceAtMost(MAX_PAGES)
            discovery.toc.take(MAX_PAGES).forEachIndexed { i, toc ->
                alive()
                val key = norm(toc.url)
                if (!visited.add(key)) return@forEachIndexed
                onProgress("Fetching page\n${i + 1} / $total")
                val page = if (same(toc.url, first.url)) first else runCatching { WebFetch.page(toc.url) }.getOrNull()
                if (page == null) return@forEachIndexed
                alive()
                onProgress("Analysing page ${i + 1} / $total")
                val parse = if (same(toc.url, first.url)) firstParse else PageAnalyzer.analyze(page.html, page.url)
                val label = toc.label.ifBlank { parse.title }
                chapters += toChapter(parse, dir, chapters.size, label, toc.number, cancelled, onProgress)
                if (!same(toc.url, first.url)) Thread.sleep(150)
            }
        } else {
            var page = first
            var html = first.html
            var parse = firstParse
            while (chapters.size < MAX_PAGES) {
                alive()
                val key = norm(page.url)
                if (!visited.add(key)) break
                onProgress("Analysing page ${chapters.size + 1}")
                chapters += toChapter(parse, dir, chapters.size, parse.title, ChapterDiscovery.chapterNumber(parse.title, parse.url), cancelled, onProgress)
                val nxt = ChapterDiscovery.inspect(html, page.url).next ?: break
                if (visited.contains(norm(nxt))) break
                alive()
                onProgress("Fetching page")
                val nextPage = runCatching { WebFetch.page(nxt) }.getOrNull() ?: break
                page = nextPage
                html = nextPage.html
                parse = PageAnalyzer.analyze(html, nextPage.url)
                Thread.sleep(150)
            }
        }
        if (chapters.isEmpty()) {
            alive()
            chapters += toChapter(
                firstParse, dir, 0, firstParse.title,
                ChapterDiscovery.chapterNumber(firstParse.title, firstParse.url),
                cancelled, onProgress
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
            ChapterDiscovery.chapterNumber(parse.title, parse.url),
            { false },
            {}
        )
    }

    private fun toChapter(
        parse: PageParse,
        dir: File,
        index: Int,
        label: String,
        number: Int?,
        cancelled: () -> Boolean,
        onProgress: (String) -> Unit
    ): WebChapter {
        val id = "ch$index"
        val title = label.ifBlank {
            number?.let { "Chapter $it" } ?: parse.url.substringAfterLast('/').ifBlank { "Page ${index + 1}" }
        }
        val cands = attachImages(parse.candidates, dir, index, cancelled, onProgress).map { it.copy(id = "${id}_${it.id}") }
        return WebChapter(id, parse.url, title, index, number, parse.kind, cands)
    }

    private fun attachImages(
        candidates: List<WebCandidate>,
        dir: File,
        chapterIndex: Int,
        cancelled: () -> Boolean,
        onProgress: (String) -> Unit
    ): List<WebCandidate> {
        val preview = candidates.filter {
            it.type == WebBlockType.Image && it.verdict != WebVerdict.Drop && !it.imageUrl.isNullOrBlank()
        }.take(40)
        if (preview.isEmpty()) return candidates
        val local = HashMap<String, String>()
        preview.forEachIndexed { i, c ->
            if (cancelled()) return@forEachIndexed
            onProgress("Downloading chapter ${chapterIndex + 1}\n${i + 1} / ${preview.size} images")
            val dest = File(dir, "c${chapterIndex}_$i.bin")
            val ok = WebFetch.image(c.imageUrl!!, dest)
            if (ok) local[c.id] = dest.absolutePath
        }
        return candidates.map { c ->
            val path = local[c.id] ?: return@map c
            c.copy(localPath = path)
        }
    }

    private fun stripChapter(title: String): String {
        val t = title.replace(Regex("(?i)\\s*[-|:]+\\s*(chapter|ch|episode|ep)\\s*\\d+.*$"), "").trim()
        return t.ifBlank { title }
    }

    private fun norm(url: String): String = url.substringBefore('#').trimEnd('/').lowercase()

    private fun same(a: String, b: String): Boolean = norm(a) == norm(b)
}
