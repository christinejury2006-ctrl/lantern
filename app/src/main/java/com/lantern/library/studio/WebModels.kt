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

data class WebDraft(
    val phase: WebPhase,
    val url: String = "",
    val title: String = "",
    val kind: PageKind = PageKind.Text,
    val candidates: List<WebCandidate> = emptyList(),
    val error: String? = null
) {
    val kept: List<WebCandidate> get() = candidates.filter { it.verdict == WebVerdict.Keep }
    val unsure: List<WebCandidate> get() = candidates.filter { it.verdict == WebVerdict.Unsure }
}
