package com.lantern.library.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Pausing and resuming a restore, against the production [DownloadService].
 *
 * The rules under test are the ones that decide whether a reader gets their book back or a file of
 * the right length and the wrong contents:
 *
 * - a pause stops the transfer and keeps the bytes already received as a **checkpoint**;
 * - a resume continues from that checkpoint only when the journal agrees with what is on disk, and
 *   starts from zero otherwise (never a blind append);
 * - a file is placed only after size, checksum and container have all been checked, atomically;
 * - a paused restore holds no slot, so one book never gets two transfers writing one file;
 * - and no path in here deletes or alters the copy in the account.
 *
 * The fake host streams in 4 KB chunks and reports real byte counts, so the pause positions are
 * actual positions in an actual file. What is *not* tested here — and is not claimed anywhere — is
 * Google's handling of an HTTP `Range` request: [DriveLibrary.streamDownload] is where a 206 is
 * appended and a 200 is truncated, and it is exercised only by the compiler.
 */
class RestorePauseTest {

    // ---------------------------------------------------------------------------------------
    // A host that streams, and can be made to stop
    // ---------------------------------------------------------------------------------------

    private class StreamHost(val dir: File) : DownloadHost {
        val library = LinkedHashMap<String, LibraryBook>()
        val cloud = HashMap<String, ByteArray>()
        /** Offsets the service asked to continue from, in order. */
        val streamedFrom = CopyOnWriteArrayList<Long>()
        /** What the journal would say is on disk, per book. */
        val journal = HashMap<String, Long>()
        val noted = CopyOnWriteArrayList<Pair<Long, Long>>()
        val cleared = CopyOnWriteArrayList<String>()
        val phases = CopyOnWriteArrayList<TransferPhase>()
        val notes = CopyOnWriteArrayList<String>()
        var refreshes = 0

        /** False models a service that ignores `Range` and sends the whole file every time. */
        var honourRange = true
        /** Invoked after each chunk, so a test can pause mid-stream at a real byte position. */
        var afterChunk: ((Long) -> Unit)? = null
        /** Blocks the stream until opened: how two restores are made to overlap. */
        var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        /** Size of the chunk written per step. */
        val chunk = 4096

        override fun latest(bookId: String): LibraryBook? = library[bookId]

        override fun persist(book: LibraryBook): Boolean {
            library[book.id] = book
            return true
        }

        override val booksDir: File get() = dir

        override suspend fun accountHolds(book: LibraryBook): AccountLink = AccountLink.MATCHED

        override fun deleteLocal(file: File): Boolean = file.delete()

        override suspend fun fetchCloud(book: LibraryBook, dest: File): Boolean {
            val bytes = cloud[book.id] ?: return false
            dest.writeBytes(bytes)
            return true
        }

        override suspend fun streamCloud(
            book: LibraryBook,
            dest: File,
            from: Long,
            control: RestoreControl,
            onBytes: (received: Long, total: Long) -> Unit
        ): StreamReport {
            streamedFrom += from
            entered.countDown()
            gate?.await(20, TimeUnit.SECONDS)
            val bytes = cloud[book.id] ?: return StreamReport(0, -1, false, false, false, "missing")
            val start = if (from > 0L && honourRange) from.toInt() else 0
            val slice = if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes
            dest.parentFile?.mkdirs()
            var written = 0L
            java.io.FileOutputStream(dest, start > 0).use { out ->
                var i = 0
                while (i < slice.size) {
                    val n = minOf(chunk, slice.size - i)
                    out.write(slice, i, n)
                    i += n
                    written += n
                    onBytes(start + written, bytes.size.toLong())
                    afterChunk?.invoke(start + written)
                    if (control.paused() || control.cancelled()) {
                        out.flush()
                        return StreamReport(written, bytes.size.toLong(), false, true, true, "paused")
                    }
                }
                out.flush()
            }
            return StreamReport(written, bytes.size.toLong(), complete = true, paused = false, retryable = false)
        }

        override fun resumeOffset(bookId: String): Long = journal[bookId] ?: 0L

        override fun noteRestore(bookId: String, received: Long, total: Long) {
            journal[bookId] = received
            noted += received to total
        }

        override fun clearRestore(bookId: String) {
            journal.remove(bookId)
            cleared += bookId
        }

        override fun noteRestoreProgress(bookId: String, received: Long, total: Long, phase: TransferPhase) {
            phases += phase
        }

        override fun refreshAudit() {
            refreshes++
        }

        override fun note(message: String) {
            notes += message
        }
    }

    private lateinit var dir: File
    private lateinit var host: StreamHost
    private lateinit var service: DownloadService

    @Before
    fun setUp() {
        dir = File.createTempFile("lore-restore", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        host = StreamHost(dir)
        service = DownloadService(host)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun epubBytes(seed: String, size: Int = 40_000): ByteArray {
        val body = StringBuilder("PK\u0003\u0004META-INF/container.xml mimetype application/epub+zip ")
        while (body.length < size) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A confirmed cloud copy whose local bytes are gone: the state a restore starts from. */
    private fun remoteBook(id: String = "book-1", bytes: ByteArray = epubBytes("body")): LibraryBook {
        host.cloud[id] = bytes
        val book = LibraryBook(
            id = id,
            title = "A Book",
            author = "An Author",
            format = BookFormat.EPUB,
            origin = BookOrigin.IMPORT,
            filePath = null,
            pageCount = 100,
            currentPage = 12,
            driveFileId = "drive-$id",
            backupAt = 1_700_000_000_000L,
            backupBytes = bytes.size.toLong(),
            backupMd5 = md5(bytes)
        )
        host.library[id] = book
        return book
    }

    private fun localFile(id: String = "book-1") = host.library[id]!!.filePath?.let(::File)

    private fun tempFile(id: String = "book-1") =
        File(dir, BookFiles.cacheNameFor(id, BookFormat.EPUB) + ".restore")

    // =======================================================================================
    // Pausing
    // =======================================================================================

    // 1
    @Test
    fun `a pause before the first byte leaves nothing on disk`() {
        val book = remoteBook()
        service.pause(book.id)

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Paused, got $outcome", outcome is RestoreOutcome.Paused)
        val paused = outcome as RestoreOutcome.Paused
        assertEquals("no bytes arrived", 0L, paused.received)
        assertFalse("nothing to resume from", paused.resumable)
        assertFalse("no partial file may be left", tempFile().exists())
        assertNull("and no local file", localFile())
        assertEquals("the cloud copy is untouched", book.backupBytes, host.cloud[book.id]!!.size.toLong())
    }

    // 2
    @Test
    fun `a pause mid-stream keeps exactly the bytes that arrived`() {
        val book = remoteBook()
        host.afterChunk = { received ->
            if (received >= 12_000L) service.pause(book.id)
        }

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Paused, got $outcome", outcome is RestoreOutcome.Paused)
        val paused = outcome as RestoreOutcome.Paused
        assertTrue("some bytes must have arrived", paused.received > 0L)
        assertTrue("and not all of them", paused.received < book.backupBytes)
        assertTrue("the checkpoint must be offered as resumable", paused.resumable)
        assertEquals("the bytes on disk are the checkpoint", paused.received, tempFile().length())
        assertEquals(
            "the journal must record the same offset",
            paused.received,
            host.journal[book.id]
        )
        assertEquals("the checkpoint must not be cleared", 0, host.cleared.size)
        assertTrue("paused is a phase the panel must be told about", TransferPhase.PAUSED in host.phases)
        assertTrue("verification must not have run", TransferPhase.VERIFYING !in host.phases)
    }

    // 3
    @Test
    fun `a resume continues from the checkpoint and finishes the book`() {
        val book = remoteBook()
        host.afterChunk = { received -> if (received >= 12_000L) service.pause(book.id) }
        val first = runBlocking { service.restoreDownload(book.id) } as RestoreOutcome.Paused
        host.afterChunk = null

        service.resume(book.id)
        val second = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Restored, got $second", second is RestoreOutcome.Restored)
        assertEquals(
            "the second attempt must continue, not restart",
            first.received,
            host.streamedFrom.last()
        )
        val file = localFile()!!
        assertTrue("the book must be on disk", file.exists())
        assertEquals(
            "and be exactly the account's bytes",
            host.cloud[book.id]!!.size,
            file.length().toInt()
        )
        assertTrue("byte for byte", host.cloud[book.id]!!.contentEquals(file.readBytes()))
        assertFalse("the temporary file must be gone", tempFile().exists())
        assertTrue("the checkpoint must be cleared once the book is home", book.id in host.cleared)
    }

    // 4
    @Test
    fun `a checkpoint the journal disagrees with is thrown away, not appended to`() {
        val book = remoteBook()
        // Bytes on disk, and a journal that claims a different offset: this is the debris of an
        // interrupted write, and continuing from it would build a corrupt book of the right length.
        tempFile().writeBytes(ByteArray(3_000) { 7 })
        host.journal[book.id] = 1_000L

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Restored, got $outcome", outcome is RestoreOutcome.Restored)
        assertEquals("it must start from zero", 0L, host.streamedFrom.first())
        assertTrue("and the file must be the account's copy", host.cloud[book.id]!!.contentEquals(localFile()!!.readBytes()))
    }

    // 5
    @Test
    fun `a checkpoint larger than the cloud file is thrown away`() {
        val book = remoteBook(bytes = epubBytes("small", size = 6_000))
        tempFile().writeBytes(ByteArray(9_000) { 3 })
        host.journal[book.id] = 9_000L

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Restored, got $outcome", outcome is RestoreOutcome.Restored)
        assertEquals("a checkpoint past the end is not a checkpoint", 0L, host.streamedFrom.first())
        assertEquals(6_000L, localFile()!!.length())
    }

    // 6
    @Test
    fun `a paused restore holds no slot, so the book can be resumed at once`() {
        val book = remoteBook()
        host.afterChunk = { received -> if (received >= 8_000L) service.pause(book.id) }
        val first = runBlocking { service.restoreDownload(book.id) } as RestoreOutcome.Paused
        assertFalse("a paused restore is not running", service.isRestoring(book.id))

        host.afterChunk = null
        service.resume(book.id)
        val second = runBlocking { service.restoreDownload(book.id) }

        assertTrue("the resume must not be refused as busy, got $second", second is RestoreOutcome.Restored)
        assertTrue("and it must really continue", host.streamedFrom.last() >= first.received)
    }

    // 7
    @Test
    fun `two restores of one book never run at once`() {
        val book = remoteBook()
        val gate = CountDownLatch(1)
        host.gate = gate
        val firstOutcome = arrayOfNulls<RestoreOutcome>(1)
        val runner = Thread(Runnable {
            firstOutcome[0] = runBlocking { service.restoreDownload(book.id) }
        }, "restore-first").apply { isDaemon = true; start() }

        try {
            assertTrue("the first restore must start", host.entered.await(20, TimeUnit.SECONDS))
            val second = runBlocking { service.restoreDownload(book.id) }
            assertEquals("a second writer on one file is refused", RestoreOutcome.Busy, second)
        } finally {
            gate.countDown()
        }
        runner.join(30_000)
        assertTrue(
            "the first restore must complete: ${firstOutcome[0]}",
            firstOutcome[0] is RestoreOutcome.Restored
        )
        assertEquals("and write one file", 1, host.streamedFrom.size)
    }

    // 8
    @Test
    fun `a resume after a restart still continues from the checkpoint`() {
        val book = remoteBook()
        host.afterChunk = { received -> if (received >= 10_000L) service.pause(book.id) }
        val paused = runBlocking { service.restoreDownload(book.id) } as RestoreOutcome.Paused

        // The process died and came back: a new service, the same host (so the same journal).
        val restarted = DownloadService(host)
        host.afterChunk = null
        val outcome = runBlocking { restarted.restoreDownload(book.id) }

        assertTrue("expected Restored, got $outcome", outcome is RestoreOutcome.Restored)
        assertEquals("the checkpoint must survive a restart", paused.received, host.streamedFrom.last())
    }

    // =======================================================================================
    // Verifying and safety
    // =======================================================================================

    // 9
    @Test
    fun `bytes that fail the checksum are refused and nothing is placed`() {
        val good = epubBytes("good")
        val book = remoteBook(bytes = good)
        // A different file of exactly the same length: the failure a size check alone cannot see.
        val wrong = ByteArray(good.size) { i -> if (i < 20) good[i] else (good[i] + 1).toByte() }
        host.cloud[book.id] = wrong

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertTrue(
            "the reason must mention the checksum: $outcome",
            (outcome as RestoreOutcome.Failed).reason.contains("checksum")
        )
        assertFalse("no local file may appear", File(dir, BookFiles.cacheNameFor(book.id, BookFormat.EPUB)).exists())
        assertFalse("and the partial must be gone", tempFile().exists())
        assertNull("the record must still point at no file", localFile())
    }

    // 10
    @Test
    fun `a file that is not a book container is refused even at the right size and checksum`() {
        val junk = ByteArray(8_000) { 'x'.code.toByte() }
        val book = remoteBook(bytes = junk)

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertTrue(
            "the container check must be the reason: $outcome",
            (outcome as RestoreOutcome.Failed).reason.contains("valid book")
        )
        assertNull("nothing may be placed", localFile())
        assertFalse("and no half-file left behind", tempFile().exists())
    }

    // 11
    @Test
    fun `the account copy is never altered by pausing, resuming or failing`() {
        val book = remoteBook()
        val before = host.cloud[book.id]!!.copyOf()
        host.afterChunk = { received -> if (received >= 12_000L) service.pause(book.id) }
        runBlocking { service.restoreDownload(book.id) }
        host.afterChunk = null
        service.resume(book.id)
        runBlocking { service.restoreDownload(book.id) }

        assertTrue("the cloud copy must be byte-identical", before.contentEquals(host.cloud[book.id]!!))
        assertEquals("and still the recorded size", book.backupBytes, host.cloud[book.id]!!.size.toLong())
    }

    // 12
    @Test
    fun `a pause is never reported as a completed restore`() {
        val book = remoteBook()
        host.afterChunk = { received -> if (received >= 8_000L) service.pause(book.id) }

        val outcome = runBlocking { service.restoreDownload(book.id) }

        assertFalse("a pause is not a completion", outcome is RestoreOutcome.Restored)
        assertNull("and must not leave a book to open", localFile())
        assertTrue("it is a pause", outcome is RestoreOutcome.Paused)
        assertTrue("with the bytes it got", (outcome as RestoreOutcome.Paused).received > 0L)
        assertTrue(
            "the file is only ready once verification has run: ${host.phases}",
            TransferPhase.VERIFYING !in host.phases
        )
    }
}
