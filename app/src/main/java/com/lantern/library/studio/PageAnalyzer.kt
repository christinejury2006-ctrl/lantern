package com.lantern.library.studio

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PageAnalyzer {
    private val JUNK = listOf(
        "nav", "header", "footer", "aside",
        "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
        ".navbar", ".nav-bar", ".site-header", ".site-footer", ".sidebar", "#sidebar",
        ".comments", "#comments", ".comment-list", "#disqus_thread",
        ".advertisement", ".adsbygoogle", ".ad-slot", ".cookie", ".cookie-banner",
        ".share-buttons", ".social-share", ".related-posts", ".recommended",
        ".popup", ".modal", ".newsletter"
    ).joinToString(",")

    private val MAIN_HINT = Regex(
        "(^|\\s)(chapter-content|entry-content|post-content|reader-content|novel-content|" +
            "manga-content|episode-content|chapter-inner|post-body|article-content)(\\s|$)",
        RegexOption.IGNORE_CASE
    )

    private val NOTE_HINT = Regex("note|afterword|author|foreword|disclaimer|announcement", RegexOption.IGNORE_CASE)
    private val NAV_IMG = Regex("prev|next|arrow|button|icon|sprite|logo|avatar|emoji|smilie|pixel", RegexOption.IGNORE_CASE)

    fun analyze(html: String, baseUri: String): PageParse {
        val doc = Jsoup.parse(html, baseUri)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.title().trim() }
        doc.select("script, style, noscript, iframe, form, button, svg, canvas").remove()
        doc.select(JUNK).remove()
        val body = doc.body() ?: return PageParse(baseUri, title, PageKind.Text, emptyList())
        val raw = collect(body)
        return PageParse(baseUri, title, detectKind(raw), raw)
    }

    private fun collect(root: Element): List<WebCandidate> {
        val out = ArrayList<WebCandidate>()
        var n = 0
        fun id() = "w${n++}"
        val seenText = HashSet<String>()
        val seenImg = HashSet<String>()
        val nodes = root.select("p, h1, h2, h3, h4, li, blockquote, pre, figure, img, div")
        for (el in nodes) {
            if (el.tagName() == "div" && el.children().any { it.tagName() != "br" && it.tagName() != "span" }) continue
            val imgs = if (el.tagName() == "img") listOf(el) else el.select("> img, > a > img, > figure > img")
            if (imgs.isNotEmpty() && el.text().length < 40) {
                for (img in imgs) {
                    val url = imageUrl(img) ?: continue
                    if (!seenImg.add(url)) continue
                    out += classifyImage(id(), url, img, inMain(el))
                }
                continue
            }
            val text = el.text().replace(Regex("\\s+"), " ").trim()
            if (text.length < 25) continue
            if (!seenText.add(text.take(180))) continue
            out += classifyText(id(), text, el, inMain(el))
        }
        return out.take(80)
    }

    private fun inMain(el: Element): Boolean {
        var cur: Element? = el
        while (cur != null) {
            val tag = cur.tagName()
            if (tag == "article" || tag == "main" || cur.hasAttr("role") && cur.attr("role").equals("main", true)) return true
            val blob = (cur.className() + " " + cur.id())
            if (MAIN_HINT.containsMatchIn(blob)) return true
            cur = cur.parent()
        }
        return false
    }

    private fun classifyText(id: String, text: String, el: Element, main: Boolean): WebCandidate {
        val links = el.select("a").sumOf { it.text().length }
        val density = if (text.isEmpty()) 0f else links.toFloat() / text.length
        val note = NOTE_HINT.containsMatchIn(el.className() + el.id() + text.take(80))
        val (verdict, reason) = when {
            density > 0.55f && text.length < 500 -> WebVerdict.Drop to "Mostly links (navigation or related)."
            note -> WebVerdict.Unsure to "This block may be an author's note."
            text.length >= 220 && density < 0.28f -> WebVerdict.Keep to if (main) "Main text." else "Long text block."
            main && text.length >= 80 && density < 0.35f -> WebVerdict.Keep to "Text in the main article."
            text.length >= 40 -> WebVerdict.Unsure to "Short or mixed text — may be content."
            else -> WebVerdict.Drop to "Too short to be chapter text."
        }
        return WebCandidate(id, WebBlockType.Text, verdict, reason, text = text)
    }

    private fun classifyImage(id: String, url: String, img: Element, main: Boolean): WebCandidate {
        val w = img.attr("width").toIntOrNull() ?: 0
        val h = img.attr("height").toIntOrNull() ?: 0
        val blob = (img.className() + " " + img.id() + " " + url + " " + img.attr("alt"))
        val (verdict, reason) = when {
            (w in 1..59 && h in 1..59) -> WebVerdict.Drop to "Tiny image (icon or spacer)."
            NAV_IMG.containsMatchIn(blob) && w < 200 && h < 200 ->
                WebVerdict.Drop to "Likely an icon or site chrome."
            NAV_IMG.containsMatchIn(blob) ->
                WebVerdict.Unsure to "This image may be navigation rather than content."
            main && (w >= 200 || h >= 200 || (w == 0 && h == 0)) ->
                WebVerdict.Keep to "Content image in the main article."
            w >= 400 || h >= 400 ->
                WebVerdict.Keep to "Large image — possible comic page."
            w == 0 && h == 0 ->
                WebVerdict.Unsure to "This image may be a comic page."
            else ->
                WebVerdict.Unsure to "This image may be a comic page."
        }
        return WebCandidate(id, WebBlockType.Image, verdict, reason, imageUrl = url)
    }

    private fun imageUrl(img: Element): String? {
        val raw = listOf("src", "data-src", "data-original", "data-lazy-src", "data-url")
            .map { img.absUrl(it).ifBlank { img.attr(it) } }
            .firstOrNull { it.startsWith("http") }
            ?: srcsetFirst(img)
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
    }

    private fun srcsetFirst(img: Element): String? {
        val set = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        val first = set.split(',').firstOrNull()?.trim()?.substringBefore(' ')?.trim().orEmpty()
        return when {
            first.startsWith("http") -> first
            first.isNotEmpty() -> img.absUrl("srcset").ifBlank { null }
            else -> null
        }
    }

    private fun detectKind(candidates: List<WebCandidate>): PageKind {
        val useful = candidates.filter { it.verdict != WebVerdict.Drop }
        val textChars = useful.filter { it.type == WebBlockType.Text }.sumOf { it.text.length }
        val images = useful.count { it.type == WebBlockType.Image }
        return if (images >= 3 && images * 350 >= textChars) PageKind.Images else PageKind.Text
    }
}
