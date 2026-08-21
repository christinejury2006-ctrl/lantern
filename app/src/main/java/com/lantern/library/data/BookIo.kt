package com.lantern.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
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
        val coverFile = File(coversDir(context), "$id.jpg")
        val coverOk = if (format == BookFormat.EPUB) extractEpubCover(dest, coverFile) else extractPdfCover(dest, coverFile)
        val title = if (format == BookFormat.EPUB) {
            epubDcTitle(dest)?.takeIf { it.isNotBlank() } ?: cleanImportTitle(name)
        } else {
            cleanImportTitle(name)
        }
        val pages = if (format == BookFormat.PDF) pdfPageCount(dest) else estimateEpubPages(dest)
        return LibraryBook(
            id = id,
            title = title,
            author = "Imported",
            remoteCover = if (coverOk) coverFile.absolutePath else null,
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

    fun cleanImportTitle(filename: String): String {
        var s = filename.substringBeforeLast('.')
        listOf(
            "oceanofpdf.com", "oceanofpdf", "www.oceanofpdf.com",
            "allnovel", "pdfdrive", "pdf-drive", "z-library", "zlib", "libgen"
        ).forEach { prefix ->
            s = s.replace(Regex("(?i)" + Regex.escape(prefix)), " ")
        }
        s = s.replace('_', ' ').replace('-', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(?i)^(?:www\\s+|com\\s+)+"), "").trim()
        return s.ifBlank { "Imported book" }
    }

    private fun epubDcTitle(file: File): String? {
        val zip = try { ZipFile(file) } catch (_: Exception) { return null }
        zip.use { z ->
            val opf = findOpf(z) ?: return null
            val xml = runCatching { z.getInputStream(opf).bufferedReader().use { it.readText() } }.getOrNull() ?: return null
            val raw = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(xml)?.groupValues?.getOrNull(1) ?: return null
            return stripHtml(raw).trim().takeIf { it.isNotBlank() }
        }
    }

    private fun extractEpubCover(epub: File, dest: File): Boolean {
        val zip = try { ZipFile(epub) } catch (_: Exception) { return false }
        return zip.use { z ->
            val opf = findOpf(z)
            var href: String? = null
            if (opf != null) {
                val xml = runCatching { z.getInputStream(opf).bufferedReader().use { it.readText() } }.getOrNull().orEmpty()
                val coverId = Regex("""(?is)<meta[^>]+name=["']cover["'][^>]*content=["']([^"']+)["']""").find(xml)?.groupValues?.getOrNull(1)
                    ?: Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]*name=["']cover["']""").find(xml)?.groupValues?.getOrNull(1)
                if (!coverId.isNullOrBlank()) {
                    val item = Regex("""(?is)<item[^>]+id=["']${Regex.escape(coverId)}["'][^>]*>""").find(xml)
                    href = item?.let { Regex("""href=["']([^"']+)["']""").find(it.value)?.groupValues?.getOrNull(1) }
                }
                if (href == null) {
                    href = Regex("""(?is)<item[^>]+properties=["'][^"']*cover-image[^"']*["'][^>]*href=["']([^"']+)["']""").find(xml)?.groupValues?.getOrNull(1)
                        ?: Regex("""(?is)<item[^>]+href=["']([^"']+)["'][^>]*properties=["'][^"']*cover-image""").find(xml)?.groupValues?.getOrNull(1)
                }
                if (!href.isNullOrBlank()) {
                    val base = opf.name.substringBeforeLast('/', "")
                    val path = listOfNotNull(base.takeIf { it.isNotEmpty() }, href.trim().trimStart('/')).joinToString("/")
                    val entry = z.getEntry(path) ?: z.getEntry(href.trim())
                    if (entry != null && writeZipImage(z, entry, dest)) return@use true
                }
            }
            val images = z.entries().toList().filter { e ->
                val n = e.name.lowercase()
                !e.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
            }
            val preferred = images.firstOrNull { it.name.lowercase().contains("cover") }
                ?: images.maxByOrNull { it.size }
            preferred != null && writeZipImage(z, preferred, dest)
        }
    }

    private fun findOpf(zip: ZipFile): ZipEntry? {
        val container = zip.getEntry("META-INF/container.xml")
        if (container != null) {
            val xml = runCatching { zip.getInputStream(container).bufferedReader().use { it.readText() } }.getOrNull().orEmpty()
            val path = Regex("""full-path=["']([^"']+)["']""").find(xml)?.groupValues?.getOrNull(1)
            if (!path.isNullOrBlank()) zip.getEntry(path)?.let { return it }
        }
        return zip.entries().toList().firstOrNull { it.name.lowercase().endsWith(".opf") }
    }

    private fun writeZipImage(zip: ZipFile, entry: ZipEntry, dest: File): Boolean {
        return runCatching {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.isEmpty()) return false
            val name = entry.name.lowercase()
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                dest.writeBytes(bytes)
            } else {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
                dest.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
                bmp.recycle()
            }
            dest.exists() && dest.length() > 0L
        }.getOrDefault(false)
    }

    private fun extractPdfCover(pdf: File, dest: File): Boolean {
        return runCatching {
            val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount < 1) return@runCatching false
                    renderer.openPage(0).use { page ->
                        val w = 400
                        val h = ((w.toFloat() * page.height) / page.width).toInt().coerceIn(120, 640)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        dest.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
                        bmp.recycle()
                    }
                }
                dest.exists() && dest.length() > 0L
            } finally {
                runCatching { pfd.close() }
            }
        }.getOrDefault(false)
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }
}
