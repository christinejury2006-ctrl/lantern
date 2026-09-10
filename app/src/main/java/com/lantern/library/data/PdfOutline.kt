package com.lantern.library.data

import android.content.Context
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File

/**
 * Reads native PDF bookmarks via PDFium, then closes the document.
 * Does not render pages. Destinations are 0-based page indices.
 */
object PdfOutline {
    private const val MAX_ITEMS = 400

    fun read(context: Context, file: File): List<TocEntry> {
        if (!file.exists() || file.length() < 16L) return emptyList()
        var pfd: ParcelFileDescriptor? = null
        var core: PdfiumCore? = null
        var doc: PdfDocument? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            core = PdfiumCore(context.applicationContext)
            doc = core.newDocument(pfd)
            val out = ArrayList<TocEntry>()
            flatten(core.getTableOfContents(doc), 0, out)
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            val opened = doc
            val engine = core
            if (opened != null && engine != null) {
                runCatching { engine.closeDocument(opened) }
            } else {
                runCatching { pfd?.close() }
            }
        }
    }

    private fun flatten(nodes: List<PdfDocument.Bookmark>, level: Int, out: MutableList<TocEntry>) {
        nodes.forEach { node ->
            if (out.size >= MAX_ITEMS) return
            val title = node.title?.trim().orEmpty()
            val page = node.pageIdx.toInt()
            if (title.isNotEmpty()) {
                out += TocEntry(
                    title = title,
                    href = "",
                    level = level.coerceAtLeast(0),
                    chapterIndex = if (page >= 0) page else -1
                )
            }
            if (node.hasChildren()) flatten(node.children, level + 1, out)
        }
    }
}
