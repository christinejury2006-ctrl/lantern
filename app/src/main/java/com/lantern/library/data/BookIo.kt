package com.lantern.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object BookIo {

    fun booksDir(context: Context): File =
        File(context.getExternalFilesDir(null), "books").apply { mkdirs() }

    fun coversDir(context: Context): File =
        File(context.getExternalFilesDir(null), "covers").apply { mkdirs() }

    fun cloudDir(context: Context): File =
        File(context.getExternalFilesDir(null), "cloud").apply { mkdirs() }

    fun importUri(context: Context, uri: Uri): LibraryBook? {
        val name = displayName(context, uri) ?: return null
        val lower = name.lowercase()
        val format = when {
            lower.endsWith(".epub") -> BookFormat.EPUB
            lower.endsWith(".pdf") -> BookFormat.PDF
            else -> return null
        }
        val id = "imp_${System.currentTimeMillis()}_${name.hashCode().toUInt()}"
        val dest = File(booksDir(context), "$id.${if (format == BookFormat.EPUB) "epub" else "pdf"}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        } ?: return null
        val title = name.substringBeforeLast('.')
        val pages = if (format == BookFormat.PDF) pdfPageCount(dest) else estimateEpubPages(dest)
        return LibraryBook(
            id = id,
            title = title,
            author = "Imported",
            format = format,
            origin = BookOrigin.IMPORT,
            filePath = dest.absolutePath,
            pageCount = pages.coerceAtLeast(1),
            category = "Imported",
            synopsis = "Imported from this phone."
        )
    }

    fun importTree(context: Context, tree: Uri, limit: Int = 400): List<LibraryBook> {
        val out = mutableListOf<LibraryBook>()
        val cr = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        walkTree(context, tree, children, out, limit)
        return out
    }

    private fun walkTree(
        context: Context,
        tree: Uri,
        children: Uri,
        out: MutableList<LibraryBook>,
        limit: Int
    ) {
        if (out.size >= limit) return
        val cr = context.contentResolver
        val cursor = try {
            cr.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
        } catch (_: Exception) {
            null
        } ?: return
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val docId = it.getString(0) ?: continue
                val name = it.getString(1) ?: ""
                val mime = it.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    walkTree(context, tree, child, out, limit)
                } else {
                    val lower = name.lowercase()
                    if (lower.endsWith(".epub") || lower.endsWith(".pdf") ||
                        mime.contains("epub") || mime == "application/pdf"
                    ) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        importUri(context, uri)?.let { book -> out += book }
                    }
                }
            }
        }
    }

    fun downloadCatalog(context: Context, remote: CatalogBook, preferPdf: Boolean): LibraryBook? {
        val url = if (preferPdf && remote.pdf != null) remote.pdf else remote.epub ?: remote.pdf ?: return null
        val format = if (url == remote.pdf) BookFormat.PDF else BookFormat.EPUB
        val id = "pg_${remote.remoteId}"
        val ext = if (format == BookFormat.PDF) "pdf" else "epub"
        val dest = File(booksDir(context), "$id.$ext")
        val bytes = Gutendex.downloadBytes(url) ?: return null
        dest.writeBytes(bytes)
        var coverPath: String? = null
        remote.cover?.let { c ->
            runCatching {
                val img = Gutendex.downloadBytes(c)
                if (img != null) {
                    val cf = File(coversDir(context), "$id.jpg")
                    cf.writeBytes(img)
                    coverPath = cf.absolutePath
                }
            }
        }
        val pages = if (format == BookFormat.PDF) pdfPageCount(dest) else estimateEpubPages(dest)
        return LibraryBook(
            id = id,
            title = remote.title,
            author = remote.author,
            remoteCover = remote.cover,
            format = format,
            origin = BookOrigin.DOWNLOAD,
            filePath = dest.absolutePath,
            remoteEpub = remote.epub,
            remotePdf = remote.pdf,
            pageCount = pages.coerceAtLeast(1),
            category = remote.subjects.ifBlank { "Catalog" },
            synopsis = remote.subjects
        ).let { if (coverPath != null) it.copy(remoteCover = coverPath) else it }
    }

    fun readEpubChapters(file: File): List<Chapter> {
        val zip = try { ZipFile(file) } catch (_: Exception) { return emptyList() }
        zip.use { z ->
            val htmlEntries = z.entries().toList()
                .filter { e ->
                    val n = e.name.lowercase()
                    !e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) &&
                        !n.contains("nav") && !n.contains("toc")
                }
                .sortedBy { it.name }
            return htmlEntries.mapIndexed { i, entry ->
                val raw = z.getInputStream(entry).bufferedReader().use { it.readText() }
                val text = stripHtml(raw)
                Chapter(titleFromHtml(raw) ?: "Chapter ${i + 1}", text.ifBlank { " " })
            }.filter { it.body.isNotBlank() }
        }
    }

    fun pdfPageCount(file: File): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { it.pageCount }.also { pfd.close() }
        } catch (_: Exception) {
            1
        }
    }

    private fun estimateEpubPages(file: File): Int {
        val chapters = readEpubChapters(file)
        val chars = chapters.sumOf { it.body.length }
        return (chars / 900).coerceAtLeast(1)
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?>.*?</style>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("&#\\d+;"), " ")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun titleFromHtml(html: String): String? {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
            ?: Regex("(?is)<h1[^>]*>(.*?)</h1>").find(html)
        return m?.groupValues?.getOrNull(1)?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }
}
