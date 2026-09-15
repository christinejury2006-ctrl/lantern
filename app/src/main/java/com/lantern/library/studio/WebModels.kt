package com.lantern.library.studio

enum class PageKind { Text, Images }

enum class WebBlockType { Text, Image }

enum class WebVerdict { Keep, Drop, Unsure }

enum class WebPhase { Fetching, Ready, Failed }

data class WebCandidate(
    val id: String,
    val type: WebBlockType,
    val verdict: WebVerdict,
    val reason: String,
    val text: String = "",
    val imageUrl: String? = null,
    val localPath: String? = null
)

data class WebChapter(
    val id: String,
    val url: String,
    val title: String,
    val index: Int,
    val detectedNumber: Int?,
    val kind: PageKind,
    val candidates: List<WebCandidate>
) {
    val hasUnsure: Boolean get() = candidates.any { it.verdict == WebVerdict.Unsure }
    val kept: List<WebCandidate> get() = candidates.filter { it.verdict == WebVerdict.Keep }
    val unsure: List<WebCandidate> get() = candidates.filter { it.verdict == WebVerdict.Unsure }
}

data class WebLinkGuess(
    val id: String,
    val url: String,
    val label: String,
    val reason: String
)

data class PageParse(
    val url: String,
    val title: String,
    val kind: PageKind,
    val candidates: List<WebCandidate>
)

data class WebDraft(
    val phase: WebPhase,
    val url: String = "",
    val title: String = "",
    val kind: PageKind = PageKind.Text,
    val chapters: List<WebChapter> = emptyList(),
    val possible: List<WebLinkGuess> = emptyList(),
    val openChapterId: String? = null,
    val progress: String = "",
    val error: String? = null
) {
    val open: WebChapter?
        get() = chapters.firstOrNull { it.id == openChapterId } ?: chapters.firstOrNull()
    val kept: List<WebCandidate> get() = open?.kept.orEmpty()
    val unsure: List<WebCandidate> get() = open?.unsure.orEmpty()
}
