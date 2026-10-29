package com.lantern.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
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

    /**
     * Downloads a [FreeEdition] from an open catalogue into the library.
     *
     * The bytes are written to a temporary file first and run through [BookFiles.validate]. A book
     * only reaches the shelf if the file really is the container its URL claimed: a 404 page, an
     * HTML error, a truncated download or a lending-wall redirect all fail here and return null,
     * so Explore can say "that download failed" instead of quietly adding a broken book.
     */
    fun downloadEdition(context: Context, edition: FreeEdition): LibraryBook? {
        val bytes = OpenCatalogs.fetchBytes(edition.url) ?: return null
        val ext = if (edition.format == BookFormat.PDF) "pdf" else "epub"
        // Stable id per edition, so re-downloading the same book updates a file instead of
        // growing a second copy on the shelf.
        val id = "free_" + edition.id.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(60)
        val dest = File(booksDir(context), "$id.$ext")
        val temp = File(dest.absolutePath + ".part")
        return try {
            temp.writeBytes(bytes)
            when (val check = BookFiles.validate(temp, edition.format)) {
                is BookFiles.Check.Invalid -> {
                    temp.delete()
                    null
                }
                BookFiles.Check.Ok -> {
                    if (!temp.renameTo(dest)) {
                        temp.delete()
                        return null
                    }
                    var coverPath: String? = null
                    edition.coverUrl?.let { url ->
                        runCatching {
                            val img = OpenCatalogs.fetchBytes(url)
                            if (img != null && img.size < 4 * 1024 * 1024) {
                                val cf = File(coversDir(context), "$id.jpg")
                                cf.writeBytes(img)
                                coverPath = cf.absolutePath
                            }
                        }
                    }
                    val pages = if (edition.format == BookFormat.PDF) pdfPageCount(dest) else estimateEpubPages(dest)
                    val title = epubDcTitle(dest)?.takeIf { it.isNotBlank() } ?: edition.title.ifBlank { "Untitled" }
                    val author = epubDcCreators(dest).joinToString(", ").ifBlank { edition.author }
                    LibraryBook(
                        id = id,
                        title = title,
                        author = author,
                        remoteCover = coverPath ?: edition.coverUrl,
                        format = edition.format,
                        origin = BookOrigin.DOWNLOAD,
                        filePath = dest.absolutePath,
                        pageCount = pages.coerceAtLeast(1),
                        category = "Downloaded",
                        synopsis = "Downloaded from ${edition.source}. ${edition.rights}."
                    )
                }
            }
        } catch (t: Throwable) {
            temp.delete()
            null
        }
    }

    fun readEpubChapters(file: File): List<Chapter> = readEpubDocument(file).chapters

    /**
     * Reads an EPUB into chapters, unpacking its pictures alongside the book once.
     *
     * Text-only stripping is what made illustrated books unreadable: a page that is a picture has
     * no text at all, so the old reader showed a nearly empty chapter. Markup is now walked in
     * document order and each `<img>` (or SVG `<image>`) becomes an [EpubMedia] marker paragraph in
     * the position it occupies, so pictures and prose stay in the order the book was designed in.
     *
     * The images themselves are extracted next to the book on first open — into
     * `media_<book file name>/` — and never again: a twelve-picture novel costs one pass, and a
     * hundred-picture children's book still opens straight into reading the second time.
     */
    fun readEpubDocument(file: File): EpubDocument {
        val zip = try { ZipFile(file) } catch (_: Exception) { return EpubDocument(emptyList(), emptyList()) }
        zip.use { z ->
            val opf = findOpf(z)
            val opfXml = opf?.let { entry ->
                runCatching { z.getInputStream(entry).bufferedReader().use { it.readText() } }.getOrNull()
            }.orEmpty()
            val opfDir = opf?.name?.substringBeforeLast('/', "")?.let { if (it == opf.name) "" else it }.orEmpty()
            val manifest = parseOpfManifest(opfXml)
            val spineIds = parseOpfSpine(opfXml)
            val media = unpackMedia(z, file)
            val chapters = mutableListOf<Chapter>()
            if (spineIds.isNotEmpty() && manifest.isNotEmpty()) {
                spineIds.forEach { id ->
                    val item = manifest[id] ?: return@forEach
                    if (isNavOrNcx(item)) return@forEach
                    if (!isHtmlItem(item)) return@forEach
                    val path = resolveEpubPath(opfDir, item.href)
                    val entry = zipEntry(z, path) ?: zipEntry(z, item.href) ?: return@forEach
                    val raw = runCatching { z.getInputStream(entry).bufferedReader().use { it.readText() } }.getOrNull() ?: return@forEach
                    val body = blocksOf(raw, entry.name, media)
                    val href = normalizeEpubPath(entry.name)
                    chapters += Chapter(
                        titleFromHtml(raw) ?: item.href.substringAfterLast('/'),
                        body.ifBlank { " " },
                        href
                    )
                }
            }
            if (chapters.isEmpty()) {
                z.entries().toList()
                    .filter { e ->
                        val n = e.name.lowercase()
                        !e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) &&
                            !n.contains("nav") && !n.contains("toc") && !n.endsWith(".ncx")
                    }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        val raw = z.getInputStream(entry).bufferedReader().use { it.readText() }
                        val body = blocksOf(raw, entry.name, media)
                        chapters += Chapter(
                            titleFromHtml(raw) ?: entry.name.substringAfterLast('/'),
                            body.ifBlank { " " },
                            normalizeEpubPath(entry.name)
                        )
                    }
            }
            // Some books hide the cover in the archive rather than in the spine. If no chapter
            // already shows it, open the book with it — the cover is part of the book.
            val cover = coverEntry(z, opf, opfXml)
            val coverPath = cover?.let { media[normalizeEpubPath(it.name)]?.absolutePath }
            if (coverPath != null && chapters.none { it.body.contains(EpubMedia.MARK + coverPath) }) {
                val size = imageSize(File(coverPath)) ?: (600 to 900)
                chapters.add(
                    0,
                    Chapter("Cover", EpubMedia.marker(coverPath, size.first, size.second), "")
                )
            }
            val toc = parseEpubToc(z, opfDir, opfXml, manifest, chapters)
            return EpubDocument(chapters, toc)
        }
    }

    /**
     * Walks one XHTML file in order, turning text runs into paragraphs and each picture into a
     * marker.
     *
     * A regex over `<img>` is enough here — after tags are stripped the text is what it is, and the
     * only thing that matters is where in the document each picture sat. `<svg><image href>` is
     * handled too because that is how a large number of covers and full-page plates are authored.
     */
    private fun blocksOf(raw: String, entryName: String, media: Map<String, File>): String {
        val baseDir = entryName.substringBeforeLast('/', "")
        val out = StringBuilder()
        var cursor = 0
        val pattern = Regex("""(?is)<img\b[^>]*>|<image\b[^>]*>""")
        fun flush(until: Int) {
            if (until <= cursor) return
            val text = stripHtml(raw.substring(cursor, until))
            if (text.isNotBlank()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(text)
            }
        }
        for (match in pattern.findAll(raw)) {
            val tag = match.value
            val src = attr(tag, "src") ?: attr(tag, "xlink:href") ?: attr(tag, "href") ?: continue
            val resolved = resolveEpubPath(baseDir, src)
            val file = media[resolved] ?: media[normalizeEpubPath(src)] ?: continue
            flush(match.range.first)
            cursor = match.range.last + 1
            val size = imageSize(file) ?: continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(EpubMedia.marker(file.absolutePath, size.first, size.second))
        }
        flush(raw.length)
        return out.toString().trim()
    }

    /** One attribute value from a raw tag, tolerant of quotes and of namespaced names. */
    private fun attr(tag: String, name: String): String? {
        val quoted = Regex("""(?is)\b${Regex.escape(name)}\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted
        return Regex("""(?is)\b${Regex.escape(name)}\s*=\s*([^\s>]+)""").find(tag)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    /** Pixel size of an image file, read from its header without decoding the whole bitmap. */
    private fun imageSize(file: File): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    /**
     * Unpacks every image in the archive once, keyed by its normalised entry name.
     *
     * A finished pass leaves a marker file so reopening the book does not rescan (and, for a
     * picture book, does not rewrite a hundred JPEGs every time the reader taps it). Vector images
     * are skipped: they cannot be drawn without an SVG renderer, and a missing illustration is
     * better than a broken page.
     */
    private fun unpackMedia(zip: ZipFile, book: File): Map<String, File> {
        val dir = File(book.parentFile, "media_" + book.name)
        val done = File(dir, ".unpacked")
        val map = HashMap<String, File>()
        val imageEntries = zip.entries().toList().filter { e ->
            val n = e.name.lowercase()
            !e.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp"))
        }
        if (done.exists()) {
            imageEntries.forEach { e ->
                val f = File(dir, mediaName(e.name))
                if (f.exists() && f.length() > 0L) map[normalizeEpubPath(e.name)] = f
            }
            return map
        }
        dir.mkdirs()
        imageEntries.forEach { e ->
            val target = File(dir, mediaName(e.name))
            val wrote = runCatching {
                zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                target.length() > 0L
            }.getOrDefault(false)
            if (wrote) map[normalizeEpubPath(e.name)] = target else runCatching { target.delete() }
        }
        runCatching { done.writeText(System.currentTimeMillis().toString()) }
        return map
    }

    /** A flat, filesystem-safe name for an archive entry, unique per source path. */
    private fun mediaName(entryName: String): String {
        val lower = entryName.lowercase()
        val ext = lower.substringAfterLast('.', "jpg").let { if (it.length <= 4) it else "jpg" }
        val hash = entryName.hashCode().toUInt().toString(16)
        val stem = lower.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^a-z0-9]+"), "_").take(28)
        return "${stem}_$hash.$ext"
    }

    /** The archive entry the book's own metadata calls its cover, if there is one. */
    private fun coverEntry(zip: ZipFile, opf: ZipEntry?, opfXml: String): ZipEntry? {
        if (opf != null) {
            val coverId = Regex("""(?is)<meta[^>]+name=["']cover["'][^>]*content=["']([^"']+)["']""")
                .find(opfXml)?.groupValues?.getOrNull(1)
                ?: Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]*name=["']cover["']""")
                    .find(opfXml)?.groupValues?.getOrNull(1)
            val byId = coverId?.let { id ->
                Regex("""(?is)<item[^>]+id=["']${Regex.escape(id)}["'][^>]*>""").find(opfXml)?.value
                    ?.let { tag -> attr(tag, "href") }
            }
            val byProp = Regex("""(?is)<item[^>]+properties=["'][^"']*cover-image[^"']*["'][^>]*>""")
                .find(opfXml)?.value?.let { tag -> attr(tag, "href") }
            val href = byId ?: byProp
            if (!href.isNullOrBlank()) {
                val base = opf?.name?.substringBeforeLast('/', "").orEmpty()
                zipEntry(zip, resolveEpubPath(base, href))?.let { return it }
            }
        }
        val images = zip.entries().toList().filter { e ->
            val n = e.name.lowercase()
            !e.isDirectory && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
        }
        return images.firstOrNull { it.name.lowercase().contains("cover") }
            ?: images.maxByOrNull { it.size }
    }

    fun pdfPageCount(file: File): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { it.pageCount }.also { pfd.close() }
        } catch (_: Exception) {
            1
        }
    }

    fun readPdfOutline(context: Context, file: File): List<TocEntry> = PdfOutline.read(context, file)

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
        s = s.replace(Regex("(?i)</(p|h[1-6]|li|blockquote|tr|section|article|header|footer)>"), "\n\n")
        s = s.replace(Regex("(?i)</(div|td|th|dt|dd)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        return s.replace('\u00ad', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun decodeEntities(raw: String): String {
        var s = raw
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&rsquo;", "'").replace("&lsquo;", "'")
            .replace("&rdquo;", "\"").replace("&ldquo;", "\"")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&hellip;", "...").replace("&shy;", "")
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " "
        }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: " "
        }
        return s
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

    private fun epubDcCreators(file: File): List<String> {
        val zip = try { ZipFile(file) } catch (_: Exception) { return emptyList() }
        return zip.use { z ->
            val opf = findOpf(z) ?: return@use emptyList()
            val xml = runCatching { z.getInputStream(opf).bufferedReader().use { it.readText() } }.getOrNull()
                ?: return@use emptyList()
            Regex("(?is)<dc:creator[^>]*>(.*?)</dc:creator>").findAll(xml).mapNotNull { m ->
                stripHtml(m.groupValues[1]).trim().takeIf { it.isNotBlank() }
            }.distinct().toList()
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

    private data class OpfItem(val id: String, val href: String, val mediaType: String, val properties: String)

    private fun parseOpfManifest(opfXml: String): Map<String, OpfItem> {
        val out = linkedMapOf<String, OpfItem>()
        Regex("""(?is)<item\b([^>]+)/?>""").findAll(opfXml).forEach { m ->
            val attrs = tagAttrs(m.groupValues[1])
            val id = attrs["id"].orEmpty()
            val href = attrs["href"].orEmpty()
            if (id.isBlank() || href.isBlank()) return@forEach
            out[id] = OpfItem(id, href, attrs["media-type"].orEmpty(), attrs["properties"].orEmpty())
        }
        return out
    }

    private fun parseOpfSpine(opfXml: String): List<String> {
        val spine = Regex("""(?is)<spine\b[^>]*>(.*?)</spine>""").find(opfXml)?.groupValues?.getOrNull(1) ?: opfXml
        return Regex("""(?is)<itemref\b([^>]+)/?>""").findAll(spine).mapNotNull { m ->
            tagAttrs(m.groupValues[1])["idref"]?.takeIf { it.isNotBlank() }
        }.toList()
    }

    private fun parseEpubToc(
        zip: ZipFile,
        opfDir: String,
        opfXml: String,
        manifest: Map<String, OpfItem>,
        chapters: List<Chapter>
    ): List<TocEntry> {
        val navItem = manifest.values.firstOrNull { it.properties.split(Regex("\\s+")).any { p -> p.equals("nav", true) } }
        val ncxItem = manifest.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", true) || it.href.lowercase().endsWith(".ncx") }
            ?: tagAttrs(Regex("""(?is)<spine\b([^>]*)>""").find(opfXml)?.groupValues?.getOrNull(1).orEmpty())["toc"]
                ?.let { id -> manifest[id] }
        val navEntries = if (navItem != null) {
            val path = resolveEpubPath(opfDir, navItem.href)
            val entry = zipEntry(zip, path) ?: zipEntry(zip, navItem.href)
            val xml = entry?.let { runCatching { zip.getInputStream(it).bufferedReader().use { r -> r.readText() } }.getOrNull() }
            val base = path.substringBeforeLast('/', "")
            xml?.let { parseNavXhtml(it, base) }.orEmpty()
        } else emptyList()
        val ncxEntries = if (ncxItem != null) {
            val path = resolveEpubPath(opfDir, ncxItem.href)
            val entry = zipEntry(zip, path) ?: zipEntry(zip, ncxItem.href)
            val xml = entry?.let { runCatching { zip.getInputStream(it).bufferedReader().use { r -> r.readText() } }.getOrNull() }
            val base = path.substringBeforeLast('/', "")
            xml?.let { parseNcx(it, base) }.orEmpty()
        } else emptyList()
        val byHref = linkedMapOf<String, TocEntry>()
        navEntries.forEach { byHref[it.href] = it }
        ncxEntries.forEach { if (it.href !in byHref) byHref[it.href] = it }
        val raw = byHref.values.toList()
        return raw.map { entry ->
            val chapterIndex = indexForTocHref(entry.href, chapters)
            entry.copy(chapterIndex = chapterIndex)
        }.filter { it.title.isNotBlank() }
    }

    private fun parseNavXhtml(xml: String, baseDir: String): List<TocEntry> {
        val parsed = runCatching { parseNavWithPull(xml, baseDir) }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed
        return parseNavFallback(xml, baseDir)
    }

    private fun parseNavWithPull(xml: String, baseDir: String): List<TocEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val out = mutableListOf<TocEntry>()
        var inToc = false
        var tocDepth = 0
        var olDepth = 0
        var inAnchor = false
        var href = ""
        val title = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().lowercase()
                    if (name == "nav") {
                        val type = navTypeAttr(parser)
                        if (type.split(Regex("\\s+")).any { it.equals("toc", true) }) {
                            inToc = true
                            tocDepth = 1
                            olDepth = 0
                        } else if (inToc) tocDepth++
                    } else if (inToc && name == "ol") {
                        olDepth++
                    } else if (inToc && name == "a") {
                        inAnchor = true
                        href = parser.getAttributeValue(null, "href").orEmpty()
                        title.clear()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inAnchor) title.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty().lowercase()
                    if (inAnchor && name == "a") {
                        inAnchor = false
                        val label = stripHtml(title.toString()).trim()
                        if (label.isNotBlank() && href.isNotBlank() && !href.startsWith("javascript:", true)) {
                            out += TocEntry(
                                title = label,
                                href = resolveEpubPath(baseDir, href.substringBefore('#')),
                                level = (olDepth - 1).coerceAtLeast(0)
                            )
                        }
                        href = ""
                    } else if (inToc && name == "ol") {
                        olDepth = (olDepth - 1).coerceAtLeast(0)
                    } else if (name == "nav" && inToc) {
                        tocDepth--
                        if (tocDepth <= 0) inToc = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseNavFallback(xml: String, baseDir: String): List<TocEntry> {
        val block = Regex("""(?is)<nav\b[^>]*(?:epub:type|type)\s*=\s*["'][^"']*\btoc\b[^"']*["'][^>]*>(.*?)</nav>""")
            .find(xml)?.groupValues?.getOrNull(1) ?: return emptyList()
        val out = mutableListOf<TocEntry>()
        var olDepth = 0
        Regex("""(?is)</?ol\b[^>]*>|<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""").findAll(block).forEach { m ->
            val token = m.value.lowercase()
            when {
                token.startsWith("<ol") -> olDepth++
                token.startsWith("</ol") -> olDepth = (olDepth - 1).coerceAtLeast(0)
                else -> {
                    val href = m.groupValues[1]
                    val label = stripHtml(m.groupValues[2]).trim()
                    if (label.isNotBlank() && href.isNotBlank()) {
                        out += TocEntry(
                            title = label,
                            href = resolveEpubPath(baseDir, href.substringBefore('#')),
                            level = (olDepth - 1).coerceAtLeast(0)
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseNcx(xml: String, baseDir: String): List<TocEntry> {
        val parsed = runCatching { parseNcxWithPull(xml, baseDir) }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed
        return parseNcxFallback(xml, baseDir)
    }

    private fun parseNcxWithPull(xml: String, baseDir: String): List<TocEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val out = mutableListOf<TocEntry>()
        var depth = 0
        var inText = false
        var pendingTitle = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty().lowercase()
                    if (name == "navpoint") {
                        depth++
                        pendingTitle = ""
                    } else if (name == "text") {
                        inText = true
                    } else if (name == "content") {
                        val src = parser.getAttributeValue(null, "src").orEmpty()
                        val label = stripHtml(pendingTitle).trim()
                        if (label.isNotBlank() && src.isNotBlank()) {
                            out += TocEntry(
                                title = label,
                                href = resolveEpubPath(baseDir, src.substringBefore('#')),
                                level = (depth - 1).coerceAtLeast(0)
                            )
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inText) pendingTitle += parser.text.orEmpty()
                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty().lowercase()
                    if (name == "text") inText = false
                    else if (name == "navpoint") depth = (depth - 1).coerceAtLeast(0)
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseNcxFallback(xml: String, baseDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        Regex("""(?is)<navPoint\b[^>]*>\s*<navLabel>\s*<text>(.*?)</text>.*?<content\b[^>]*src\s*=\s*["']([^"']+)["']""")
            .findAll(xml).forEach { m ->
                val label = stripHtml(m.groupValues[1]).trim()
                val src = m.groupValues[2]
                if (label.isNotBlank() && src.isNotBlank()) {
                    out += TocEntry(label, resolveEpubPath(baseDir, src.substringBefore('#')), 0)
                }
            }
        return out
    }

    private fun navTypeAttr(parser: XmlPullParser): String {
        val bits = ArrayList<String>()
        val ops = parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
        if (!ops.isNullOrBlank()) bits += ops
        for (i in 0 until parser.attributeCount) {
            val n = parser.getAttributeName(i).orEmpty()
            if (n.equals("type", true) || n.endsWith(":type", true) || n.equals("epub:type", true)) {
                bits += parser.getAttributeValue(i).orEmpty()
            }
        }
        return bits.joinToString(" ")
    }

    private fun indexForTocHref(href: String, chapters: List<Chapter>): Int {
        val want = normalizeEpubPath(href)
        if (want.isBlank()) return -1
        val exact = chapters.indexOfFirst { it.href == want }
        if (exact >= 0) return exact
        val pathHits = chapters.indices.filter { i ->
            val have = chapters[i].href
            have.isNotBlank() && (have.endsWith("/$want") || want.endsWith("/$have"))
        }
        if (pathHits.size == 1) return pathHits.first()
        val wantFile = want.substringAfterLast('/')
        if (wantFile.isBlank()) return -1
        val fileHits = chapters.indices.filter { chapters[it].href.substringAfterLast('/') == wantFile }
        if (fileHits.size == 1) return fileHits.first()
        return -1
    }

    private fun isNavOrNcx(item: OpfItem): Boolean {
        val props = item.properties.split(Regex("\\s+"))
        val href = item.href.lowercase()
        return props.any { it.equals("nav", true) } ||
            item.mediaType.equals("application/x-dtbncx+xml", true) ||
            href.endsWith(".ncx") ||
            href.endsWith("nav.xhtml") || href.endsWith("nav.html") ||
            href.substringAfterLast('/') == "toc.xhtml" || href.substringAfterLast('/') == "toc.html"
    }

    private fun isHtmlItem(item: OpfItem): Boolean {
        val href = item.href.lowercase()
        val mime = item.mediaType.lowercase()
        return href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm") ||
            mime.contains("html") || mime.contains("xhtml")
    }

    private fun tagAttrs(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*["']([^"']*)["']""").findAll(raw).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return out
    }

    private fun zipEntry(zip: ZipFile, path: String): ZipEntry? {
        val decoded = decodeHref(path).replace('\\', '/')
        zip.getEntry(decoded)?.let { return it }
        zip.getEntry(path)?.let { return it }
        val want = normalizeEpubPath(decoded)
        return zip.entries().toList().firstOrNull { normalizeEpubPath(it.name) == want }
    }

    private fun resolveEpubPath(baseDir: String, href: String): String {
        val path = decodeHref(href.substringBefore('#')).replace('\\', '/')
        if (path.startsWith("/")) return normalizeEpubPath(path)
        val combined = when {
            path.isBlank() -> baseDir
            baseDir.isBlank() -> path
            else -> "$baseDir/$path"
        }
        return normalizeEpubPath(combined)
    }

    private fun normalizeEpubPath(path: String): String {
        val parts = mutableListOf<String>()
        decodeHref(path).replace('\\', '/').lowercase().split('/').forEach { p ->
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeHref(value: String): String {
        val trimmed = value.trim()
        return runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
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
