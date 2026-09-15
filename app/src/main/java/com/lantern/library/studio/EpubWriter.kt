package com.lantern.library.studio

import com.lantern.library.data.BookIo
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object EpubWriter {
    fun buildModel(draft: WebDraft): CompiledBook {
        val chapters = draft.chapters.map { ch ->
            val kept = ch.candidates.filter { it.verdict == WebVerdict.Keep }
            CompiledChapter(
                title = ch.title.ifBlank { "Untitled" },
                paragraphs = kept.filter { it.type == WebBlockType.Text }.map { it.text }.filter { it.isNotBlank() },
                imageFiles = kept.filter { it.type == WebBlockType.Image }.mapNotNull { it.localPath ?: it.imageUrl }
            )
        }.filter { it.paragraphs.isNotEmpty() || it.imageFiles.isNotEmpty() }
        return CompiledBook(
            title = draft.title.trim().ifBlank { "Untitled" },
            author = draft.author.trim(),
            series = draft.series.trim(),
            kind = draft.kind,
            coverPath = draft.coverPath,
            chapters = chapters.ifEmpty {
                listOf(CompiledChapter(draft.title.ifBlank { "Untitled" }, listOf("This book has no extracted text."), emptyList()))
            }
        )
    }

    fun write(model: CompiledBook, dest: File, cacheDir: File): File? {
        dest.parentFile?.mkdirs()
        val work = File(cacheDir, "studio/web/pack")
        work.deleteRecursively()
        work.mkdirs()
        val oebps = File(work, "OEBPS").apply { mkdirs() }
        val imgDir = File(oebps, "images").apply { mkdirs() }
        val id = "lore-" + UUID.randomUUID().toString()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val manifest = StringBuilder()
        val spine = StringBuilder()
        var coverHref: String? = null
        model.coverPath?.let { path ->
            val src = resolveFile(path, cacheDir)
            if (src != null && src.exists()) {
                val ext = imageExt(src)
                val name = "cover.$ext"
                src.copyTo(File(oebps, name), overwrite = true)
                coverHref = name
                manifest.append("""    <item id="cover-img" href="$name" media-type="${imageMime(ext)}" properties="cover-image"/>""").append('\n')
                File(oebps, "cover.xhtml").writeText(xhtml("Cover", """<div><img src="$name" alt="Cover"/></div>"""))
                manifest.append("""    <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""    <itemref idref="cover-page"/>""").append('\n')
            }
        }
        model.chapters.forEachIndexed { i, ch ->
            val body = StringBuilder()
            ch.paragraphs.forEach { p ->
                body.append("<p>").append(esc(p)).append("</p>\n")
            }
            ch.imageFiles.forEachIndexed { n, raw ->
                val src = resolveFile(raw, cacheDir) ?: return@forEachIndexed
                if (!src.exists() || src.length() < 40L) return@forEachIndexed
                val ext = imageExt(src)
                val href = "images/c${i}_$n.$ext"
                src.copyTo(File(oebps, href), overwrite = true)
                manifest.append("""    <item id="img${i}_$n" href="$href" media-type="${imageMime(ext)}"/>""").append('\n')
                if (ch.paragraphs.isEmpty()) body.append("<p>").append(esc("Page ${n + 1}")).append("</p>\n")
                body.append("""<p><img src="$href" alt=""/></p>""").append('\n')
            }
            if (body.isEmpty()) body.append("<p>.</p>")
            val href = "ch${i + 1}.xhtml"
            File(oebps, href).writeText(xhtml(ch.title, body.toString()))
            manifest.append("""    <item id="ch${i + 1}" href="$href" media-type="application/xhtml+xml"/>""").append('\n')
            spine.append("""    <itemref idref="ch${i + 1}"/>""").append('\n')
        }
        val navItems = model.chapters.mapIndexed { i, ch ->
            """      <li><a href="ch${i + 1}.xhtml">${esc(ch.title)}</a></li>"""
        }.joinToString("\n")
        File(oebps, "nav.xhtml").writeText(
            xhtml(
                "Contents",
                """<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><h1>Contents</h1><ol>
$navItems
</ol></nav>"""
            )
        )
        manifest.append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""").append('\n')
        val creator = if (model.author.isBlank()) "" else "    <dc:creator>${esc(model.author)}</dc:creator>\n"
        val series = if (model.series.isBlank()) "" else "    <meta property=\"belongs-to-collection\">${esc(model.series)}</meta>\n"
        File(oebps, "content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">$id</dc:identifier>
    <dc:title>${esc(model.title)}</dc:title>
    <dc:language>en</dc:language>
$creator$series    <meta property="dcterms:modified">$now</meta>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>
"""
        )
        File(work, "META-INF").mkdirs()
        File(work, "META-INF/container.xml").writeText(
            """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
        )
        if (dest.exists()) dest.delete()
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            putStored(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zipAll(zip, work, work)
        }
        return if (validate(dest)) dest else {
            dest.delete()
            null
        }
    }

    fun validate(file: File): Boolean {
        if (!file.exists() || file.length() < 200L) return false
        val zip = runCatching { ZipFile(file) }.getOrNull() ?: return false
        zip.use { z ->
            if (z.getEntry("mimetype") == null) return false
            if (z.getEntry("META-INF/container.xml") == null) return false
            if (z.getEntry("OEBPS/content.opf") == null) return false
        }
        val doc = runCatching { BookIo.readEpubDocument(file) }.getOrNull() ?: return false
        return doc.chapters.isNotEmpty()
    }

    private fun resolveFile(raw: String, cacheDir: File): File? {
        val f = File(raw)
        if (f.exists()) return f
        if (raw.startsWith("http")) {
            val dest = File(WebFetch.webDir(cacheDir), "dl_${raw.hashCode().toUInt()}.bin")
            if (dest.exists() && dest.length() > 40L) return dest
            return if (WebFetch.image(raw, dest)) dest else null
        }
        return null
    }

    private fun imageExt(file: File): String {
        val b = runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(12)
                val n = ins.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrNull() ?: return "jpg"
        return when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "png"
            b.size >= 6 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() -> "gif"
            b.size >= 12 && b[8] == 0x57.toByte() -> "webp"
            else -> "jpg"
        }
    }

    private fun imageMime(ext: String) = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun xhtml(title: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${esc(title)}</title><meta charset="utf-8"/></head>
<body>
$body
</body>
</html>
"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun putStored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        e.crc = crc.value
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }

    private fun zipAll(zip: ZipOutputStream, root: File, dir: File) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) zipAll(zip, root, f)
            else {
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (rel == "mimetype") return@forEach
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
