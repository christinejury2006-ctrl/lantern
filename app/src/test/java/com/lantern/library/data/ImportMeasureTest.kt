package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Measuring a chosen file: the code that used to crash and read every byte.
 *
 * ## What the old code did
 *
 * `BookIo.measure` learned a file's size by reading it from beginning to end, and collected the
 * first 64 KiB in a hand-grown array with this shape:
 *
 * ```kotlin
 * val take = minOf(read, buffer.size - headRead)
 * if (head.isEmpty()) head = ByteArray(take)      // sized from the FIRST chunk only
 * buffer.copyInto(head, headRead, 0, take)        // then indexed past its end
 * headRead += take
 * ```
 *
 * `head` was allocated with the length of the first chunk. As soon as the provider returned a
 * short first read — which `content://` providers do constantly, and SAF providers do when a
 * document is streamed from a cloud provider — the second chunk was copied past the end of the
 * array and the import died with `ArrayIndexOutOfBoundsException` part-way through the selection.
 * That is the crash in the report: a Kotlin array bounds check inside the measure step, surfaced
 * through a coroutine whose cancellation was then *also* recorded (the "Suppressed:
 * ...{Cancelling}" line), which is why the cancellation looked like the cause. It is not. It is
 * what the dispatcher printed after the exception unwound.
 *
 * The scan in [ImportMeasure] is written so that case cannot exist: the windows are allocated once
 * at their fixed size, every copy is bounded by what has actually arrived, and the loop never
 * assumes a full read.
 */
class ImportMeasureTest {

    /** A stream that returns at most [chunk] bytes per `read`, like a real provider. */
    private class ShortReadStream(
        private val data: ByteArray,
        private val chunk: Int,
        private val skipSupported: Boolean = true
    ) : InputStream() {
        private var at = 0
        var reads = 0
        var bytesRead = 0L
        var skipped = 0L

        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            reads++
            val n = minOf(len, chunk, data.size - at)
            System.arraycopy(data, at, b, off, n)
            at += n
            bytesRead += n
            return n
        }

        override fun skip(n: Long): Long {
            if (!skipSupported) return 0L
            val step = minOf(n, chunk.toLong(), (data.size - at).toLong())
            if (step <= 0) return 0L
            at += step.toInt()
            skipped += step
            return step
        }
    }

    private fun md5(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes, from, to - from)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pdf(size: Int, seed: String = "x"): ByteArray {
        val body = StringBuilder("%PDF-1.7\n")
        while (body.length < size) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    // 1 -------------------------------------------------------------------------------------
    // The reproduction: a short first read used to index past a too-small array.

    @Test
    fun `a stream that returns one byte at a time is measured without crashing`() {
        val data = pdf(200_000)
        val stream = ShortReadStream(data, chunk = 1)

        val result = ImportMeasure.scan(stream, sizeHint = data.size.toLong())

        assertNotNull("the scan must survive a 1-byte-per-read provider", result)
        assertEquals("and must see the whole file", data.size.toLong(), result!!.fingerprint.bytes)
        assertEquals(
            "the head hash must cover the first 64 KiB",
            md5(data, 0, ImportMeasure.HEAD_BYTES),
            result.fingerprint.headMd5
        )
        assertEquals(
            "the tail hash must cover the last 64 KiB",
            md5(data, data.size - ImportMeasure.TAIL_BYTES, data.size),
            result.fingerprint.tailMd5
        )
        assertTrue("the length is confirmed", result.fingerprint.usable)
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `a stream that returns short reads of varying size is measured`() {
        val data = pdf(300_000)
        // 7 is prime-ish and does not divide 64 KiB, so the windows are filled by ragged chunks —
        // exactly the case the old code mishandled.
        val stream = ShortReadStream(data, chunk = 7)
        val result = ImportMeasure.scan(stream, data.size.toLong())!!
        assertEquals(data.size.toLong(), result.fingerprint.bytes)
        assertEquals(md5(data, 0, ImportMeasure.HEAD_BYTES), result.fingerprint.headMd5)
    }

    // 3 -------------------------------------------------------------------------------------
    // The other half of the report: a large selection read every byte of every file.

    @Test
    fun `a provider that states a size costs at most the two windows`() {
        // 16 MB, well past any real EPUB and enough to prove the middle is never read. Reading a
        // file of this size end to end, two hundred times, is what made a large batch take minutes
        // before the sheet even appeared.
        val size = 16 * 1024 * 1024
        val data = ByteArray(size) { (it % 251).toByte() }
        data[0] = '%'.code.toByte()
        "PDF-1.7".toByteArray().copyInto(data, 1)
        val stream = ShortReadStream(data, chunk = 64 * 1024)

        val result = ImportMeasure.scan(stream, size.toLong())!!

        assertEquals("the length must still be exact", size.toLong(), result.fingerprint.bytes)
        val windows = ImportMeasure.HEAD_BYTES + ImportMeasure.TAIL_BYTES
        assertTrue(
            "a 40 MB file must cost about 128 KiB of reading, not 40 MB: read ${stream.bytesRead}",
            stream.bytesRead <= windows + 2L * ImportMeasure.BUFFER_BYTES
        )
        assertEquals(
            "and the tail must be the real end of the file",
            md5(data, size - ImportMeasure.TAIL_BYTES, size),
            result.fingerprint.tailMd5
        )
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `a provider that will not skip still produces a correct fingerprint`() {
        val data = pdf(500_000)
        val stream = ShortReadStream(data, chunk = 4096, skipSupported = false)

        val result = ImportMeasure.scan(stream, data.size.toLong())!!

        assertEquals(data.size.toLong(), result.fingerprint.bytes)
        assertEquals(md5(data, data.size - ImportMeasure.TAIL_BYTES, data.size), result.fingerprint.tailMd5)
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `a stated size that does not match the bytes read is not trusted`() {
        val data = pdf(100_000)
        // The provider claims twice the real length. A fingerprint built on that claim would make
        // two different files look identical, so the scan refuses to vouch for the length.
        val result = ImportMeasure.scan(ShortReadStream(data, 4096), sizeHint = 200_000L)!!

        assertEquals("the bytes actually seen are counted", data.size.toLong(), result.fingerprint.bytes)
        assertFalse("the length is not confirmed", result.fingerprint.usable)
        assertEquals("and therefore cannot key a duplicate", "", result.fingerprint.key)
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `two different files of the same size are not confused`() {
        // Same length and the same first kilobyte, different remainder: only a tail window can
        // tell these two apart, which is why the fingerprint has one.
        val prefix = pdf(90_000, seed = "x")
        val other = prefix.copyOf()
        other[80_000] = (other[80_000] + 1).toByte()

        val first = ImportMeasure.scan(ByteArrayInputStream(prefix), 90_000L)!!.fingerprint
        val second = ImportMeasure.scan(ByteArrayInputStream(other), 90_000L)!!.fingerprint

        assertEquals("same length", first.bytes, second.bytes)
        assertEquals("same opening", first.headMd5, second.headMd5)
        assertTrue("different files must not share a key", first.key != second.key)
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `a scan can be cancelled between chunks`() {
        val data = ByteArray(8 * 1024 * 1024) { 1 }
        var calls = 0
        val result = ImportMeasure.scan(
            ShortReadStream(data, 64 * 1024),
            sizeHint = data.size.toLong(),
            cancelled = { ++calls > 1 }
        )
        assertNull("a cancelled scan returns nothing at all", result)
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `an empty or unreadable stream is reported as nothing`() {
        assertNull(ImportMeasure.scan(ByteArrayInputStream(ByteArray(0))))
        val failing = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("gone")
        }
        val threw = runCatching { ImportMeasure.scan(failing) }
        assertTrue("an I/O failure surfaces as a throw the caller can report", threw.isFailure)
    }

    // 9 -------------------------------------------------------------------------------------

    @Test
    fun `the format is decided by the bytes when the name disagrees`() {
        assertEquals(
            "a PDF named .epub is a PDF",
            BookFormat.PDF,
            ImportMeasure.formatOf("book.epub", pdf(2000).copyOf(1024))
        )
        val zip = ByteArray(1024)
        byteArrayOf(0x50, 0x4B, 0x03, 0x04).copyInto(zip)
        assertEquals("a ZIP with no extension is an EPUB", BookFormat.EPUB, ImportMeasure.formatOf("book", zip))
        assertEquals("a name is enough when the bytes say nothing", BookFormat.PDF, ImportMeasure.formatOf("x.pdf", ByteArray(4)))
        assertNull("neither name nor bytes", ImportMeasure.formatOf("x.txt", ByteArray(4)))
    }

    // 10 ------------------------------------------------------------------------------------

    @Test
    fun `the tail window never loses or duplicates a byte`() {
        // A direct test of the sliding window: feed ragged chunks and compare with a plain
        // whole-file computation of the last N bytes.
        val capacity = 64
        val data = ByteArray(5000) { (it * 31 % 253).toByte() }
        val window = ImportMeasure.TailWindow(capacity)
        var at = 0
        val sizes = intArrayOf(1, 3, 100, 7, 64, 65, 1000, 13)
        var i = 0
        while (at < data.size) {
            val n = minOf(sizes[i++ % sizes.size], data.size - at)
            window.add(data, at, n)
            at += n
        }
        assertEquals("the window must hold exactly the last bytes", md5(data, data.size - capacity, data.size), window.md5())
    }

    // 11 ------------------------------------------------------------------------------------
    // The batch size from the report.

    @Test
    fun `two hundred and fifty files are measured with bounded memory and no crash`() {
        // Every file is 256 KiB and every stream returns 1000-byte reads, so this exercises both
        // the size-skip path and the ragged-read path at the batch size that failed.
        var total = 0L
        val fingerprints = ArrayList<String>(250)
        // One file exists in memory at a time: the loop body is the only place a book's bytes are
        // ever held, which is the property a 200+ selection depends on.
        for (i in 1..250) {
            val data = pdf(256 * 1024, seed = "book$i")
            val stream = ShortReadStream(data, chunk = 1000)
            val result = ImportMeasure.scan(stream, data.size.toLong())
            assertNotNull("file $i must be measured", result)
            total += result!!.fingerprint.bytes
            fingerprints += result.fingerprint.key
            assertTrue(
                "each file costs at most its windows: read ${stream.bytesRead} of ${data.size}",
                stream.bytesRead <= (ImportMeasure.HEAD_BYTES + ImportMeasure.TAIL_BYTES).toLong() +
                    2L * ImportMeasure.BUFFER_BYTES
            )
        }
        assertEquals(250L * 256 * 1024, total)
        assertEquals("every file has its own fingerprint", 250, fingerprints.toSet().size)
        fingerprints.forEach { assertTrue("no fingerprint may be empty", it.isNotBlank()) }
    }
}
