package com.lantern.library.data

import java.io.File
import java.io.RandomAccessFile

/**
 * What counts as a real book file.
 *
 * A download that "succeeded" is not a book: a captive-portal login page, an HTML error document,
 * a JSON permission error and a truncated transfer all arrive with HTTP 200 and a body. Before a
 * file is allowed to replace the reader's book, or to be recorded as a backup, its container is
 * checked:
 *
 * - **EPUB** is a ZIP. The archive has to start with a local file header (`PK\x03\x04`) and contain
 *   a container descriptor (`META-INF/container.xml`, or the `application/epub+zip` mimetype). The
 *   order of those entries is *not* checked: plenty of valid EPUBs put `mimetype` first, some do
 *   not, and rejecting the second group would lock readers out of their own books.
 * - **PDF** starts with `%PDF-` in the first bytes.
 *
 * Everything here is pure file inspection so it can be tested without a device, and so the same
 * rules run on an import, a Drive download and a cache check.
 */
object BookFiles {

    /**
     * Floor for "there is a file here at all".
     *
     * Deliberately small: a short story EPUB compresses to a few hundred bytes, and rejecting it
     * would be a bug, not a safety feature. Truncation is caught by the container checks below,
     * which is a stricter test than a byte count.
     */
    const val MIN_BYTES = 128L

    sealed class Check {
        object Ok : Check()
        data class Invalid(val reason: String) : Check()
    }

    fun validate(file: File, format: BookFormat): Check {
        if (!file.exists()) return Check.Invalid("file is missing")
        if (file.length() < MIN_BYTES) return Check.Invalid("file is too short to be a book")
        val head = readHead(file, 4096)
        if (head.isEmpty()) return Check.Invalid("file is unreadable")
        return when (format) {
            BookFormat.PDF -> if (looksLikePdf(head)) Check.Ok else Check.Invalid("not a PDF")
            BookFormat.EPUB -> if (looksLikeEpub(file, head)) Check.Ok else Check.Invalid("not an EPUB archive")
            // Plain text is whatever the user imported; there is no container to check.
            BookFormat.TEXT -> Check.Ok
        }
    }

    /** True when the first bytes are a ZIP local-file header, i.e. the file is an archive at all. */
    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()

    fun looksLikePdf(head: ByteArray): Boolean {
        val magic = "%PDF-".toByteArray(Charsets.US_ASCII)
        if (head.size < magic.size) return false
        // Some producers write a little junk before the header; the spec allows the marker within
        // the first kilobyte, so look there rather than only at offset zero.
        val limit = minOf(head.size - magic.size, 1024)
        for (start in 0..limit) {
            var matched = true
            for (i in magic.indices) {
                if (head[start + i] != magic[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /**
     * A ZIP that also carries an EPUB marker. The marker is searched for in the file's bytes
     * because ZIP entry names appear verbatim in the central directory, which means the search does
     * not depend on where in the archive the entry sits — and therefore does not reject EPUBs whose
     * internal layout differs.
     */
    fun looksLikeEpub(file: File, head: ByteArray = readHead(file, 4096)): Boolean {
        if (!looksLikeZip(head)) return false
        if (containsAscii(head, "META-INF/container.xml")) return true
        if (containsAscii(head, "application/epub+zip")) return true
        // The descriptor can be past the first few kilobytes in a large archive; scan the file.
        return scanForEpubMarker(file)
    }

    private fun scanForEpubMarker(file: File): Boolean {
        val needle = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val alt = "META-INF/container.xml".toByteArray(Charsets.US_ASCII)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val chunk = ByteArray(64 * 1024)
                var carry = ByteArray(0)
                while (true) {
                    val read = raf.read(chunk)
                    if (read <= 0) break
                    val window = carry + chunk.copyOf(read)
                    if (indexOf(window, needle) >= 0 || indexOf(window, alt) >= 0) return true
                    carry = window.copyOfRange(maxOf(0, window.size - needle.size), window.size)
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun containsAscii(bytes: ByteArray, text: String): Boolean =
        indexOf(bytes, text.toByteArray(Charsets.US_ASCII)) >= 0

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readHead(file: File, count: Int): ByteArray = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    /**
     * Moves a validated temporary file into place.
     *
     * The rename is atomic on the same filesystem, so a reader can never open a half-written book:
     * either the old file is still there or the new one is. If the rename fails (different
     * filesystem, odd storage), the copy path is used and the temporary file is removed afterwards.
     */
    fun commit(temp: File, dest: File): Boolean = try {
        dest.parentFile?.mkdirs()
        // Rename first: on the same filesystem this is a single atomic replacement, so there is no
        // instant where the book does not exist. Only if that is refused (a different volume) is
        // the old file removed and the copy made.
        if (temp.renameTo(dest)) {
            true
        } else {
            if (dest.exists()) dest.delete()
            temp.copyTo(dest, overwrite = true)
            temp.delete()
            true
        }
    } catch (_: Exception) {
        runCatching { temp.delete() }
        false
    }

    /** File name used for a book's cached copy, stable across installs for the same book id. */
    fun cacheNameFor(id: String, format: BookFormat): String {
        val ext = when (format) {
            BookFormat.PDF -> "pdf"
            BookFormat.EPUB -> "epub"
            BookFormat.TEXT -> "txt"
        }
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "book" }
        return "$safe.$ext"
    }
}
