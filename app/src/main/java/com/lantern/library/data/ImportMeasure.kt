package com.lantern.library.data

import java.io.InputStream
import java.security.MessageDigest

/**
 * How much of a file has to be read to recognise it again.
 *
 * A fingerprint is metadata, not verification. It exists so the review sheet can say "you already
 * have this book" *before* anything is copied, and it is built so two real books cannot be
 * confused:
 *
 * - **the exact byte count**, so a 1.4 MB and a 1.6 MB file never collide;
 * - **MD5 of the first 64 KiB**, which carries the ZIP local header and the opening chapters of an
 *   EPUB, or the PDF header and its first page objects;
 * - **MD5 of the last 64 KiB**, which carries an EPUB's central directory and a PDF's
 *   cross-reference table — the part that differs between two files produced from the same source
 *   that are nevertheless different files.
 *
 * 128 KiB of hashed content plus an exact length is not a weak fingerprint in any practical sense,
 * and it is *bounded*: identifying a 1 MB file and a 1 GB file cost the same. Even so, nothing is
 * refused on this alone — the store confirms a shelf duplicate by comparing the full bytes before
 * it refuses a file, and the review sheet treats an unusable fingerprint as "not a duplicate"
 * rather than as evidence.
 */
data class ImportFingerprint(
    /** Exact length in bytes, or the provider's stated length. 0 when neither could be had. */
    val bytes: Long,
    /** MD5 of the first [ImportMeasure.HEAD_BYTES] bytes (fewer for a short file). */
    val headMd5: String,
    /** MD5 of the last [ImportMeasure.TAIL_BYTES] bytes (fewer for a short file). */
    val tailMd5: String,
    /**
     * True only when [bytes] is known to be the file's real length.
     *
     * A length that is a guess cannot prove two files identical, so an unconfirmed fingerprint is
     * unusable for duplicate detection — which is the safe direction: the worst case is importing a
     * file that was already there, not refusing a book the reader has never seen.
     */
    val sizeKnown: Boolean = true
) {
    /** False when the file could not be read far enough to say anything about it. */
    val readable: Boolean get() = bytes > 0L && headMd5.isNotBlank()

    /**
     * True when this can prove two files identical: readable, and the length is not a guess.
     *
     * A file can be readable and still not usable for comparison — a provider that mis-states a
     * size gives a length nobody can trust. Such a file is imported; it just never causes another
     * file to be refused as a duplicate, which is the safe direction of that trade.
     */
    val usable: Boolean get() = readable && sizeKnown

    /** The comparison key. Empty when unusable, which every caller reads as "not a duplicate". */
    val key: String get() = if (!usable) "" else "$bytes:$headMd5:$tailMd5"

    companion object {
        /** The value for a file that could not be read at all. */
        val UNREADABLE = ImportFingerprint(0L, "", "", sizeKnown = false)

        /** A fingerprint for bytes already in hand: used by tests and by full-file confirmation. */
        fun of(bytes: ByteArray): ImportFingerprint = ImportFingerprint(
            bytes = bytes.size.toLong(),
            headMd5 = md5Of(bytes, 0, minOf(bytes.size, ImportMeasure.HEAD_BYTES)),
            tailMd5 = md5Of(bytes, maxOf(0, bytes.size - ImportMeasure.TAIL_BYTES), bytes.size),
            sizeKnown = true
        )

        /** MD5 of a byte range, as lowercase hex. */
        fun md5Of(bytes: ByteArray, from: Int, to: Int): String {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(bytes, from.coerceIn(0, bytes.size), (to - from).coerceIn(0, bytes.size))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Reads a chosen file cheaply, and never holds it.
 *
 * The import path used to read every selected file from beginning to end just to learn its size —
 * for a two-hundred-book selection that is gigabytes of I/O before the review sheet even appears —
 * and it accumulated the first 64 KiB of each file in a hand-grown buffer that threw
 * `ArrayIndexOutOfBoundsException` the moment a provider returned a short read. Both are fixed here,
 * and both are covered by `ImportMeasureTest`:
 *
 * - **Size from the provider when it will say.** `DocumentsProvider` reports `OpenableColumns.SIZE`
 *   for virtually every real document, so the scan reads the first 64 KiB, skips to the end and
 *   reads the last 64 KiB. A 400 MB PDF costs 128 KiB of reading, not 400 MB.
 * - **A fixed memory ceiling.** Three small buffers regardless of file size: the 1 KiB magic
 *   window, the 64 KiB tail window, and one 16 KiB read buffer.
 * - **Short reads are normal.** A `content://` stream may return one byte per call. Neither window
 *   ever assumes a full buffer, because the old code did and that is the bug this class removes.
 * - **Cancellable between chunks**, so leaving the picker does not leave a queue of files being
 *   read in the background.
 */
object ImportMeasure {

    /** Bytes hashed from the start of the file. */
    const val HEAD_BYTES = 64 * 1024

    /** Bytes hashed from the end of the file. */
    const val TAIL_BYTES = 64 * 1024

    /** The read buffer. One of these exists at a time, whatever the file's size. */
    const val BUFFER_BYTES = 16 * 1024

    /** How many leading bytes are kept for sniffing the container: enough for `%PDF-` or a ZIP. */
    const val MAGIC_BYTES = 1024

    /** What a scan learned. */
    data class Result(
        val fingerprint: ImportFingerprint,
        /** The first bytes of the file, at most [MAGIC_BYTES] of them, for format sniffing. */
        val head: ByteArray
    )

    /**
     * Scans [input].
     *
     * @param sizeHint the length the provider reported, or a value <= 0 when it would not say.
     * @param cancelled polled between chunks; a true answer abandons the scan and returns null.
     * @return null when the file is empty, unreadable or the scan was cancelled.
     */
    fun scan(
        input: InputStream,
        sizeHint: Long = -1L,
        cancelled: () -> Boolean = { false }
    ): Result? {
        val headDigest = MessageDigest.getInstance("MD5")
        var headSeen = 0L
        val magic = ByteArray(MAGIC_BYTES)
        var magicLen = 0
        val tail = TailWindow(TAIL_BYTES)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var skippedToEnd = false

        // A provider that states a size lets us skip the middle. `skip` may skip fewer bytes than
        // asked, and some providers do not support it at all, so every answer is checked rather
        // than assumed — the loop simply carries on reading if a skip falls short.
        val canSkip = sizeHint > (HEAD_BYTES + TAIL_BYTES)

        while (true) {
            if (cancelled()) return null
            val read = input.read(buffer)
            if (read <= 0) break

            if (headSeen < HEAD_BYTES) {
                val take = minOf(read.toLong(), HEAD_BYTES - headSeen).toInt()
                headDigest.update(buffer, 0, take)
                headSeen += take
            }
            if (magicLen < MAGIC_BYTES) {
                val take = minOf(read, MAGIC_BYTES - magicLen)
                System.arraycopy(buffer, 0, magic, magicLen, take)
                magicLen += take
            }
            tail.add(buffer, 0, read)
            total += read

            if (canSkip && !skippedToEnd && total >= HEAD_BYTES && total < sizeHint - TAIL_BYTES) {
                skippedToEnd = true
                var wanted = sizeHint - TAIL_BYTES - total
                while (wanted > 0) {
                    val skipped = try {
                        input.skip(wanted)
                    } catch (_: Exception) {
                        -1L
                    }
                    if (skipped <= 0L) break
                    // Skipped bytes are neither head nor tail: they count towards the length and
                    // deliberately never enter a window.
                    total += skipped
                    wanted -= skipped
                }
            }
        }
        if (total <= 0L) return null

        return Result(
            fingerprint = ImportFingerprint(
                bytes = total,
                headMd5 = headDigest.digest().joinToString("") { "%02x".format(it) },
                tailMd5 = tail.md5(),
                // The length is trusted only when the count agrees with what the provider promised.
                // A provider that under-reports or over-reports gets an unusable fingerprint, and
                // an unusable fingerprint never rejects a book.
                sizeKnown = sizeHint <= 0L || total == sizeHint
            ),
            head = magic.copyOf(magicLen.coerceAtLeast(0))
        )
    }

    /**
     * The last [capacity] bytes written to it, hashed on demand.
     *
     * A sliding window, so a 40 MB file costs 64 KiB of tail memory instead of 40 MB. The shift
     * arithmetic is the part that has to be right: a byte that leaves the window must never be
     * counted twice, and a byte that stays must never be lost.
     */
    internal class TailWindow(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var length = 0

        fun add(source: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            if (count >= capacity) {
                // Only the very end of this chunk can survive.
                System.arraycopy(source, offset + count - capacity, buffer, 0, capacity)
                length = capacity
                return
            }
            if (length + count <= capacity) {
                System.arraycopy(source, offset, buffer, length, count)
                length += count
                return
            }
            val drop = length + count - capacity
            System.arraycopy(buffer, drop, buffer, 0, length - drop)
            System.arraycopy(source, offset, buffer, length - drop, count)
            length = capacity
        }

        fun md5(): String {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(buffer, 0, length)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * The book's format, from its name where that is conclusive and from its own bytes otherwise.
     *
     * A PDF named `.epub` is a real thing (exported by a converter that never renamed the file), so
     * the bytes win when the two disagree. A ZIP that is not an EPUB is still offered as an EPUB
     * here and rejected by the container check after the copy, which reads the whole archive rather
     * than its first kilobyte.
     */
    fun formatOf(name: String, head: ByteArray): BookFormat? {
        val byMagic = when {
            BookFiles.looksLikePdf(head) -> BookFormat.PDF
            BookFiles.looksLikeZip(head) -> BookFormat.EPUB
            else -> null
        }
        if (byMagic != null) return byMagic
        val lower = name.lowercase()
        return when {
            lower.endsWith(".epub") -> BookFormat.EPUB
            lower.endsWith(".pdf") -> BookFormat.PDF
            else -> null
        }
    }
}
