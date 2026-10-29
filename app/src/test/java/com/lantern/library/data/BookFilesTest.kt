package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The gate between "a download finished" and "the reader has a book".
 *
 * Every case here is a real failure mode of a cloud download: a login page served instead of a
 * file, an API error document, a truncated transfer, an empty response. Each one must be rejected,
 * and a legitimate EPUB — including one whose `mimetype` entry is not first, which is common — must
 * be accepted.
 */
class BookFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        tmp.newFile(name).also { it.writeBytes(bytes) }

    private fun epub(name: String, mimetypeFirst: Boolean): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { zip ->
            if (mimetypeFirst) {
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("<?xml version=\"1.0\"?><container/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            // Varied prose, so the archive is a realistic size rather than a highly compressible
            // repeat that shrinks below any sane threshold.
            val chapter = buildString {
                append("<html><body>")
                repeat(400) { i -> append("word").append(i * 7919 % 100_003).append(' ') }
                append("</body></html>")
            }
            zip.write(chapter.toByteArray())
            zip.closeEntry()
        }
        return f
    }

    @Test
    fun `a well-formed epub is accepted`() {
        val f = epub("good.epub", mimetypeFirst = true)
        assertTrue(BookFiles.validate(f, BookFormat.EPUB) is BookFiles.Check.Ok)
    }

    @Test
    fun `an epub whose mimetype entry is not first is still accepted`() {
        // Rejecting these would lock readers out of perfectly good books, which is why the check
        // looks for the container descriptor rather than relying on entry order.
        val f = epub("odd-layout.epub", mimetypeFirst = false)
        assertTrue(BookFiles.validate(f, BookFormat.EPUB) is BookFiles.Check.Ok)
    }

    @Test
    fun `an html login page is not a book`() {
        val html = ("<html><body>Sign in to continue " + "x".repeat(2000) + "</body></html>")
        val f = file("portal.epub", html.toByteArray())
        assertTrue(BookFiles.validate(f, BookFormat.EPUB) is BookFiles.Check.Invalid)
    }

    @Test
    fun `a json error document is not a book`() {
        val json = """{"error":{"code":403,"message":"insufficient permissions"}}""" + " ".repeat(2000)
        assertTrue(BookFiles.validate(file("error.epub", json.toByteArray()), BookFormat.EPUB) is BookFiles.Check.Invalid)
        assertTrue(BookFiles.validate(file("error.pdf", json.toByteArray()), BookFormat.PDF) is BookFiles.Check.Invalid)
    }

    @Test
    fun `a truncated transfer is rejected`() {
        val truncated = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00) // a zip header and nothing else
        assertTrue(BookFiles.validate(file("cut.epub", truncated), BookFormat.EPUB) is BookFiles.Check.Invalid)
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(BookFiles.validate(file("empty.epub", ByteArray(0)), BookFormat.EPUB) is BookFiles.Check.Invalid)
    }

    @Test
    fun `a pdf is recognised by its header, wherever it starts`() {
        val body = "%PDF-1.7\n" + "stream\n".repeat(400) + "\n%%EOF"
        assertTrue(BookFiles.validate(file("book.pdf", body.toByteArray()), BookFormat.PDF) is BookFiles.Check.Ok)
        // Some producers precede the header with a short comment.
        val prefixed = "%!PS junk\n" + body
        assertTrue(BookFiles.validate(file("prefixed.pdf", prefixed.toByteArray()), BookFormat.PDF) is BookFiles.Check.Ok)
    }

    @Test
    fun `an epub is not a pdf and a pdf is not an epub`() {
        assertFalse(BookFiles.looksLikePdf(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(BookFiles.looksLikeZip("%PDF-1.7".toByteArray()))
    }

    @Test
    fun `commit replaces the destination only with validated content`() {
        val dest = File(tmp.root, "book.epub")
        dest.writeText("old copy")
        val good = epub("incoming.epub", mimetypeFirst = true)
        val temp = File(tmp.root, "book.epub.part")
        good.copyTo(temp, overwrite = true)

        assertTrue(BookFiles.commit(temp, dest))
        assertFalse("the temporary file must not survive the move", temp.exists())
        assertTrue(BookFiles.validate(dest, BookFormat.EPUB) is BookFiles.Check.Ok)
        assertEquals(good.length(), dest.length())
    }

    @Test
    fun `cache names are stable and safe for a book id`() {
        val name = BookFiles.cacheNameFor("web_12/34 id", BookFormat.EPUB)
        assertTrue(name.endsWith(".epub"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(" "))
        assertEquals(name, BookFiles.cacheNameFor("web_12/34 id", BookFormat.EPUB))
    }
}
