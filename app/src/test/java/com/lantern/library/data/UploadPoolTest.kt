package com.lantern.library.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The concurrent upload path, end to end, against an in-memory Drive that can be held still.
 *
 * These tests drive [UploadPool] — the production orchestration — rather than helpers around it, so
 * what they prove is what the app does on a phone: several books moving bytes at once, the bound
 * being respected, one book's verification happening while another book is still transferring, a
 * failure staying inside its own book, and no duplicate Drive file on a retry.
 *
 * Determinism comes from latches in the fake transport, never from `sleep`: every wait in here is a
 * `CountDownLatch.await` with a deadline, so a slow machine is slow and a broken test *fails*
 * instead of passing by luck.
 *
 * What is not tested, and must not be claimed: Google's own behaviour. There is no live Drive call
 * anywhere in this file.
 */
class UploadPoolTest {

    // ---------------------------------------------------------------------------------------
    // The fake account
    // ---------------------------------------------------------------------------------------

    /**
     * A Drive that stores what it is sent, and can be told to hold still at any step.
     *
     * Gates are keyed by the book's `loreId`, because that is the only stable name the client uses
     * for a book: the Drive file id does not exist until the first upload creates one.
     */
    private class GatedDrive : DriveLibrary.Transport {
        val files = ConcurrentHashMap<String, ByteArray>()
        val loreIds = ConcurrentHashMap<String, String>()
        val names = ConcurrentHashMap<String, String>()
        val creates = AtomicInteger()
        val replaces = AtomicInteger()

        /** Uploads currently inside the byte-moving step, and the high-water mark. */
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        /** Tripped as soon as an upload for that book enters; the test waits on it. */
        val uploadEntered = ConcurrentHashMap<String, CountDownLatch>()
        /** When present, an upload for that book waits here before storing its bytes. */
        val uploadHold = ConcurrentHashMap<String, CountDownLatch>()
        /** Tripped as soon as a verification for that book enters. */
        val verifyEntered = ConcurrentHashMap<String, CountDownLatch>()
        /** When present, a verification for that book waits here. */
        val verifyHold = ConcurrentHashMap<String, CountDownLatch>()

        /** loreId -> HTTP status to refuse its upload with. Never consumed: a permanent refusal. */
        val failUpload = ConcurrentHashMap<String, Int>()
        /** loreIds whose stored file is given a wrong checksum, as a corrupted copy would be. */
        val corrupt = CopyOnWriteArraySet<String>()
        /** loreIds whose first create stores the bytes but loses the response. */
        val loseResponseOnce = CopyOnWriteArraySet<String>()

        /** Every book that reached the byte-moving step, for the "both at once" assertion. */
        val uploadOrder = CopyOnWriteArrayList<String>()

        @Volatile var callCount = 0

        override fun call(req: Request): DriveLibrary.Raw? {
            callCount++
            val url = req.url
            val path = url.encodedPath
            return when {
                path == "/drive/v3/files" && req.method == "GET" -> list(url.toString())
                path == "/upload/drive/v3/files" && req.method == "POST" -> create(req)
                path.startsWith("/upload/drive/v3/files/") && req.method == "PATCH" -> replace(req, path)
                path.startsWith("/drive/v3/files/") && req.method == "GET" -> read(req, path, url.toString())
                else -> DriveLibrary.Raw(404, ByteArray(0))
            }
        }

        private fun list(fullUrl: String): DriveLibrary.Raw {
            val decoded = java.net.URLDecoder.decode(fullUrl, "UTF-8")
            val wanted = Regex("value='([^']*)'").find(decoded)?.groupValues?.get(1)
            val ids = if (wanted.isNullOrBlank()) files.keys.toList()
            else files.keys.filter { loreIds[it] == wanted }
            val json = JSONObject().put(
                "files",
                org.json.JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } }
            )
            return DriveLibrary.Raw(200, json.toString().toByteArray())
        }

        private fun create(req: Request): DriveLibrary.Raw? {
            val body = req.body as MultipartBody
            val parts = body.parts.map { readBytes(it.body) }
            val meta = JSONObject(String(parts[0], Charsets.UTF_8))
            val loreId = meta.optJSONObject("appProperties")?.optString("loreId").orEmpty()
            return enter(loreId, meta.optString("name"), parts[1], null)
        }

        private fun replace(req: Request, path: String): DriveLibrary.Raw? {
            val id = path.substringAfterLast('/')
            val loreId = loreIds[id].orEmpty()
            return enter(loreId, names[id].orEmpty(), readBytes(req.body!!), id)
        }

        /**
         * The shared byte-moving step: count itself, honour its gates, then store or refuse.
         *
         * The counters are incremented before any wait so [maxInFlight] measures what the pool
         * actually allowed through at once, which is the thing under test.
         */
        private fun enter(loreId: String, name: String, bytes: ByteArray, replacing: String?): DriveLibrary.Raw? {
            uploadEntered[loreId]?.countDown()
            uploadOrder += loreId
            val live = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(live) { a, b -> maxOf(a, b) }
            try {
                failUpload[loreId]?.let { code ->
                    return DriveLibrary.Raw(code, """{"error":{"code":$code}}""".toByteArray())
                }
                // Held still, if the test asked for it: this is the latch that proves one book's
                // verification can run while another book is mid-transfer.
                uploadHold[loreId]?.let { gate -> gate.await(20, TimeUnit.SECONDS) }

                val id = if (replacing != null) {
                    if (!files.containsKey(replacing)) return DriveLibrary.Raw(404, ByteArray(0))
                    replaces.incrementAndGet()
                    replacing
                } else {
                    creates.incrementAndGet()
                    "file-${creates.get()}-$loreId"
                }
                files[id] = bytes
                loreIds[id] = loreId
                names[id] = name
                if (loseResponseOnce.remove(loreId)) return null
                return DriveLibrary.Raw(
                    200,
                    JSONObject().put("id", id).put("name", name).toString().toByteArray()
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        private fun read(req: Request, path: String, fullUrl: String): DriveLibrary.Raw {
            val id = path.substringAfterLast('/')
            val loreId = loreIds[id].orEmpty()
            verifyEntered[loreId]?.countDown()
            verifyHold[loreId]?.let { gate -> gate.await(20, TimeUnit.SECONDS) }
            val bytes = files[id] ?: return DriveLibrary.Raw(404, ByteArray(0))
            if (fullUrl.contains("alt=media")) return DriveLibrary.Raw(200, bytes)
            val md5 = if (loreId in corrupt) md5Of("not these bytes".toByteArray()) else md5Of(bytes)
            val json = JSONObject()
                .put("id", id)
                .put("size", bytes.size.toString())
                .put("md5Checksum", md5)
            return DriveLibrary.Raw(200, json.toString().toByteArray())
        }

        private fun readBytes(body: RequestBody): ByteArray {
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readByteArray()
        }

        private fun md5Of(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(bytes)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** How many Drive files hold [loreId]. One, ever, is the only correct answer. */
        fun countFor(loreId: String): Int = loreIds.entries.count { it.value == loreId }
    }

    // ---------------------------------------------------------------------------------------
    // What the app is told, recorded
    // ---------------------------------------------------------------------------------------

    private class Recorder : UploadListener {
        val started = ConcurrentHashMap<String, Long>()
        val progress = ConcurrentHashMap<String, MutableList<Pair<Long, Long>>>()
        val verifying = CopyOnWriteArraySet<String>()
        val verified = ConcurrentHashMap<String, String>()
        val failed = ConcurrentHashMap<String, String>()
        val paused = ConcurrentHashMap<String, Int>()
        val skipped = CopyOnWriteArraySet<String>()
        val events = ConcurrentLinkedQueue<String>()

        /** Tripped when a book's verification has finished. */
        val verifiedLatch = ConcurrentHashMap<String, CountDownLatch>()

        fun latchFor(bookId: String): CountDownLatch =
            verifiedLatch.getOrPut(bookId) { CountDownLatch(1) }

        override fun onStarted(book: LibraryBook, totalBytes: Long) {
            started[book.id] = totalBytes
            events += "start:${book.id}"
        }

        override fun onProgress(book: LibraryBook, sent: Long, total: Long) {
            progress.getOrPut(book.id) { CopyOnWriteArrayList() }.add(sent to total)
        }

        override fun onVerifying(book: LibraryBook, totalBytes: Long) {
            verifying += book.id
            events += "verifying:${book.id}"
        }

        override fun onVerified(book: LibraryBook, driveFileId: String, bytes: Long) {
            verified[book.id] = driveFileId
            events += "verified:${book.id}"
            latchFor(book.id).countDown()
        }

        override fun onFailed(book: LibraryBook, failure: DriveLibrary.TransferFailure, reachedBytes: Long) {
            failed[book.id] = failure.reason
            events += "failed:${book.id}"
        }

        override fun onPaused(book: LibraryBook, sent: Long, total: Long, reason: String) {
            paused[book.id] = (paused[book.id] ?: 0) + 1
            events += "paused:${book.id}"
        }

        override fun onSkipped(book: LibraryBook, reason: String) {
            skipped += book.id
            events += "skipped:${book.id}"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private lateinit var drive: GatedDrive
    private lateinit var dir: File
    /**
     * A pool per test.
     *
     * The app uses [UploadPool.shared], but a test must not inherit another test's in-flight set:
     * a failed assertion would otherwise strand a book and make the *next* test fail for a reason
     * that has nothing to do with it.
     */
    private lateinit var pool: UploadPool

    @Before
    fun setUp() {
        drive = GatedDrive()
        dir = File.createTempFile("lore-pool", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        pool = UploadPool()
        DriveLibrary.transport = drive
        DriveLibrary.sleeper = { }
        DriveLibrary.clearError()
    }

    @After
    fun tearDown() {
        DriveLibrary.resetTransport()
        dir.deleteRecursively()
        TransferBus.clearAll()
    }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun epubBytes(seed: String, extra: Int = 0): ByteArray {
        val body = StringBuilder("PK\u0003\u0004META-INF/container.xml")
        while (body.length < 400 + extra) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun book(id: String, file: File) = LibraryBook(
        id = id,
        title = "Book $id",
        author = "An Author",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        filePath = file.absolutePath
    )

    private fun run(
        books: List<LibraryBook>,
        listener: UploadListener = Recorder()
    ): UploadPool.Result = runBlocking {
        // A deadline, not a sleep: a deadlock becomes a failure with a stack, not a hung CI job.
        withTimeout(30_000L) { pool.run("token", books, listener) }
    }

    private fun snapshot(books: List<LibraryBook>, deletions: List<Tombstone> = emptyList()) = AccountSnapshot(
        updatedAt = 1_000L,
        userId = "user-1",
        books = books,
        bookmarks = emptyList(),
        libraryAppearance = AppearLook.libraryDefault,
        readerAppearance = AppearLook.readerDefault,
        prefs = ReadingPrefs(),
        libraryAppearAt = 1_000L,
        readerAppearAt = 1_000L,
        prefsAt = 1_000L,
        bookOrder = books.map { it.id },
        deletions = deletions
    )

    // 1 -------------------------------------------------------------------------------------

    @Test
    fun `two books transfer at the same time`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))

        // Neither create may store its bytes until both have entered. A one-at-a-time loop would
        // never open the gate, so this test fails instead of quietly passing.
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        listOf("a", "b").forEach { id ->
            drive.uploadEntered[id] = entered
            drive.uploadHold[id] = release
        }
        thread("release-both") {
            if (entered.await(20, TimeUnit.SECONDS)) release.countDown()
        }

        val result = run(listOf(a, b))

        assertEquals(setOf("a", "b"), result.confirmed.keys)
        assertEquals(2, drive.maxInFlight.get())
        assertEquals(2, drive.countFor("a") + drive.countFor("b"))
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `the concurrency bound is never exceeded`() {
        val books = (1..6).map { i -> book("n$i", write("n$i.epub", epubBytes("n$i"))) }
        // Three arrivals is exactly the configured limit: once three uploads are inside, the gate
        // opens and the rest follow in their own time. If the pool let six through, maxInFlight
        // would say so.
        val three = CountDownLatch(UploadPool.MAX_CONCURRENT_UPLOADS)
        val release = CountDownLatch(1)
        books.forEach { b ->
            drive.uploadHold[b.id] = release
            drive.uploadEntered[b.id] = three
        }
        thread("release-three") {
            if (three.await(20, TimeUnit.SECONDS)) release.countDown()
        }

        val result = run(books)

        assertEquals(6, result.confirmed.size)
        assertTrue(
            "at most ${UploadPool.MAX_CONCURRENT_UPLOADS} uploads at once, saw ${drive.maxInFlight.get()}",
            drive.maxInFlight.get() <= UploadPool.MAX_CONCURRENT_UPLOADS
        )
        assertEquals(6, drive.creates.get())
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `a book is verified before a deliberately blocked upload finishes`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))

        // B is held in the middle of its transfer, indefinitely.
        val bHold = CountDownLatch(1)
        val bEntered = CountDownLatch(1)
        drive.uploadHold["b"] = bHold
        drive.uploadEntered["b"] = bEntered

        val listener = Recorder()
        val resultHolder = arrayOfNulls<UploadPool.Result>(1)
        val runner = thread("pool") { resultHolder[0] = run(listOf(a, b), listener) }

        releasing(bHold) {
            assertTrue("B must have started transferring", bEntered.await(20, TimeUnit.SECONDS))
            // A's verification must complete while B sits inside its create call.
            assertTrue(
                "A must be verified while B is still uploading",
                listener.latchFor("a").await(20, TimeUnit.SECONDS)
            )
            assertTrue("A must have reached VERIFYING", "a" in listener.verifying)
            assertNull("A must not be waiting on B", resultHolder[0])
            assertTrue("B must still be blocked", !listener.verified.containsKey("b"))
        }
        runner.join(30_000)
        assertNotNull("RUNNER DID NOT RETURN for test 3", resultHolder[0])
        assertTrue("A BOOK FAILED in test 3", resultHolder[0]!!.failed.isEmpty())
        assertTrue("A BOOK WAS SKIPPED in test 3", resultHolder[0]!!.skipped.isEmpty())
        assertEquals("WRONG CONFIRMED SET in test 3", setOf("a", "b"), resultHolder[0]!!.confirmed.keys)
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `verification of one book overlaps the transfer of another`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))

        val verifyHold = CountDownLatch(1)
        val verifyEntered = CountDownLatch(1)
        drive.verifyHold["a"] = verifyHold
        drive.verifyEntered["a"] = verifyEntered

        val bHold = CountDownLatch(1)
        val bEntered = CountDownLatch(1)
        drive.uploadHold["b"] = bHold
        drive.uploadEntered["b"] = bEntered

        val listener = Recorder()
        val holder = arrayOfNulls<UploadPool.Result>(1)
        val runner = thread("pool") { holder[0] = run(listOf(a, b), listener) }

        releasing(verifyHold, bHold) {
            assertTrue("A's verification must start", verifyEntered.await(20, TimeUnit.SECONDS))
            assertTrue("B's transfer must be in progress at the same time", bEntered.await(20, TimeUnit.SECONDS))
            assertTrue("A must be in VERIFYING, not done", "a" in listener.verifying)
            assertTrue("B must not be verified yet", !listener.verified.containsKey("b"))
        }
        runner.join(30_000)
        assertNotNull("RUNNER DID NOT RETURN for test 4", holder[0])
        assertTrue("A BOOK FAILED in test 4", holder[0]!!.failed.isEmpty())
        assertTrue("A BOOK WAS SKIPPED in test 4", holder[0]!!.skipped.isEmpty())
        assertEquals("WRONG CONFIRMED SET in test 4", setOf("a", "b"), holder[0]!!.confirmed.keys)
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `one refused upload does not stop the others`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))
        val c = book("c", write("c.epub", epubBytes("c")))
        // 403 is permanent: no retry, and it must stay inside B.
        drive.failUpload["b"] = 403

        val listener = Recorder()
        val result = run(listOf(a, b, c), listener)

        assertEquals(setOf("a", "c"), result.confirmed.keys)
        assertEquals(listOf("b"), result.failed.map { it.first.id })
        assertNotNull(listener.failed["b"])
        assertTrue("B's reason must name the refusal", listener.failed["b"]!!.contains("403"))
        assertNull(listener.verified["b"])
        assertEquals(2, drive.creates.get())
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `a retry never creates a second Drive file`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        // The first create stores the bytes and loses the answer: the classic interrupted upload.
        drive.loseResponseOnce += "a"

        val first = run(listOf(a))
        assertEquals(setOf("a"), first.confirmed.keys)
        assertEquals(1, drive.countFor("a"))

        // And a whole second pass for the same book, as the retry queue would do.
        val second = run(listOf(a))
        assertEquals(setOf("a"), second.confirmed.keys)
        assertEquals("a retry must replace the same file, never add one", 1, drive.countFor("a"))
        assertEquals("no second create", 1, drive.creates.get())
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `progress arrives for the right book, in order, and never goes backwards`() {
        val files = mapOf(
            "a" to write("a.epub", epubBytes("a", extra = 12_000)),
            "b" to write("b.epub", epubBytes("b", extra = 60_000)),
            "c" to write("c.epub", epubBytes("c", extra = 30_000))
        )
        val books = files.map { (id, file) -> book(id, file) }
        val listener = Recorder()

        val result = run(books, listener)

        assertEquals(setOf("a", "b", "c"), result.confirmed.keys)
        files.forEach { (id, file) ->
            // Every start names the book's own size: a misattributed event would show up here.
            assertEquals("start size for $id", file.length(), listener.started[id])
            val events = listener.progress[id].orEmpty()
            assertTrue("$id must report progress", events.isNotEmpty())
            var previous = -1L
            events.forEach { (sent, total) ->
                assertEquals("total for $id must be its own file", file.length(), total)
                assertTrue("$id went backwards: $previous then $sent", sent >= previous)
                previous = sent
            }
            assertEquals("$id must finish its own bytes", file.length(), previous)
            assertTrue("$id must be verified", listener.verified.containsKey(id))
        }
        // Exactly one verification per book, and no book was started twice.
        assertEquals(3, listener.events.count { it.startsWith("verifying:") })
        assertEquals(3, listener.events.count { it.startsWith("start:") })
        assertEquals(3, listener.events.count { it.startsWith("verified:") })
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `a merge and its tombstones stay correct while transfers are running`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))
        val c = book("c", write("c.epub", epubBytes("c")))

        val gate = CountDownLatch(1)
        val entered = CountDownLatch(3)
        listOf("a", "b", "c").forEach { id ->
            drive.uploadHold[id] = gate
            drive.uploadEntered[id] = entered
        }

        val listener = Recorder()
        val holder = arrayOfNulls<UploadPool.Result>(1)
        val runner = thread("pool") { holder[0] = run(listOf(a, b, c), listener) }

        releasing(gate) {
            assertTrue("all three must be transferring", entered.await(20, TimeUnit.SECONDS))

            // The reader deletes B while its file is in flight, and a merge runs against a cloud
            // copy that still has it. The tombstone must win, in the merge and in the reconciled
            // snapshot.
            val stone = Tombstone("b", System.currentTimeMillis())
            val cloud = snapshot(listOf(a, b, c), deletions = listOf(stone))
            val local = snapshot(listOf(a, b, c))

            repeat(4) {
                val merged = AccountSync.merge(local, cloud)
                assertTrue("a tombstoned book must not survive a merge", merged.books.none { it.id == "b" })
                val written = AccountReconcile.snapshot(merged, listOf(stone))
                assertTrue("a tombstoned book must not be written back", written.books.none { it.id == "b" })
                assertTrue("the deletion must travel", written.deletions.any { it.bookId == "b" })
            }
        }
        runner.join(30_000)
        // The transfer itself still completes: refusing to move bytes would not undo the deletion.
        assertNotNull("RUNNER DID NOT RETURN for test 8", holder[0])
        assertTrue("A BOOK FAILED in test 8", holder[0]!!.failed.isEmpty())
        assertTrue("A BOOK WAS SKIPPED in test 8", holder[0]!!.skipped.isEmpty())
        assertEquals("WRONG CONFIRMED SET in test 8", setOf("a", "b", "c"), holder[0]!!.confirmed.keys)
        assertEquals(1, drive.countFor("b"))
    }

    // 9 -------------------------------------------------------------------------------------

    @Test
    fun `deleting a book during its upload cannot resurrect it`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val b = book("b", write("b.epub", epubBytes("b")))

        val gate = CountDownLatch(1)
        val entered = CountDownLatch(2)
        listOf("a", "b").forEach { id ->
            drive.uploadHold[id] = gate
            drive.uploadEntered[id] = entered
        }

        val listener = Recorder()
        val holder = arrayOfNulls<UploadPool.Result>(1)
        val runner = thread("pool") { holder[0] = run(listOf(a, b), listener) }
        var written: AccountSnapshot? = null
        releasing(gate) {
            assertTrue(entered.await(20, TimeUnit.SECONDS))

            // Delete A mid-upload. The record is gone; the tombstone is what the next sync sends.
            val deletedAt = System.currentTimeMillis()
            val stone = Tombstone("a", deletedAt)
            val stalePass = snapshot(listOf(a, b)) // computed before the deletion, as a slow pass would
            val reconciled = AccountReconcile.snapshot(stalePass, listOf(stone))
            written = reconciled

            assertTrue(
                "a deleted book must not be in the snapshot this pass writes",
                reconciled.books.none { it.id == "a" }
            )
            assertTrue(
                "the older operation must not overwrite the deletion",
                reconciled.deletions.any { it.bookId == "a" }
            )
            assertTrue("A must stay deleted on this device too", Deletions.alive(listOf(a), listOf(stone)).isEmpty())
        }
        runner.join(30_000)
        // The pass still reports what the account now holds — the file was uploaded before the
        // deletion — but nothing in the written snapshot may bring the book back.
        assertNotNull("RUNNER DID NOT RETURN for test 9", holder[0])
        assertTrue("A BOOK FAILED in test 9", holder[0]!!.failed.isEmpty())
        assertEquals("WRONG CONFIRMED SET in test 9", setOf("a", "b"), holder[0]!!.confirmed.keys)
        assertTrue(written!!.books.none { it.id == "a" })
    }

    // 10 ------------------------------------------------------------------------------------

    @Test
    fun `the journal survives a restart and says truthfully what to do`() {
        val a = book("a", write("a.epub", epubBytes("a", extra = 4_000)))
        val size = File(a.filePath!!).length()
        val progress = TransferProgress.uploading("a", 1_000L, size, 5L)

        val raw = TransferJournal.encode(listOf(progress))
        val back = TransferJournal.decode(raw).single()

        assertEquals("a", back.bookId)
        assertEquals(TransferPhase.PAUSED, back.phase)
        assertEquals(1_000L, back.sentBytes)
        assertEquals(progress.totalBytes, back.totalBytes)

        // A half-sent upload of an unchanged file is retried, not accepted.
        assertEquals(
            ResumeDecision.RETRY,
            Resumable.decide(back, a, File(a.filePath!!).length())
        )
        // A file that changed while the app was away is sent again from the start.
        val changed = a.copy(backupBytes = 0L)
        assertEquals(
            ResumeDecision.RESEND,
            Resumable.decide(back, changed, File(a.filePath!!).length() + 99)
        )
        // A book deleted while we were away is never resurrected by its own journal.
        assertEquals(ResumeDecision.ALREADY_DONE, Resumable.decide(back, null, 0L))
        // And a signed, verified backup of exactly these bytes needs nothing.
        val done = a.copy(backupAt = 1L, backupBytes = File(a.filePath!!).length(), backupFailed = false)
        assertEquals(ResumeDecision.ALREADY_DONE, Resumable.decide(back, done, File(a.filePath!!).length()))

        // One entry per book, whatever order or repetition the journal is fed.
        val twice = TransferJournal.upsert(TransferJournal.upsert(emptyList(), progress), progress)
        assertEquals(1, twice.size)
        assertTrue(TransferJournal.remove(twice, "a").isEmpty())
        assertTrue(TransferJournal.decode("not json").isEmpty())
        assertTrue(TransferJournal.decode("[]").isEmpty())
    }

    // 11 ------------------------------------------------------------------------------------

    @Test
    fun `a failed verification never reports a verified book`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        // The account hands back a checksum that does not match, twice: the copy is wrong.
        drive.corrupt += "a"

        val listener = Recorder()
        val result = run(listOf(a), listener)

        assertTrue("nothing may be confirmed", result.confirmed.isEmpty())
        assertNull("no verified event may be emitted", listener.verified["a"])
        assertTrue("the book must have reached VERIFYING first", "a" in listener.verifying)
        assertNotNull("the failure must have a reason", listener.failed["a"])
        assertTrue(
            "the reason must say the copies differ: ${listener.failed["a"]}",
            listener.failed["a"]!!.contains("differ")
        )
        // Never "100% and a cloud": the badge has no way to become verified from this result.
        assertEquals(listOf("a"), result.failed.map { it.first.id })
        assertTrue("the transfer itself did happen", drive.files.isNotEmpty())
    }

    // 12 ------------------------------------------------------------------------------------

    @Test
    fun `a book another caller is already sending is skipped, not sent twice`() {
        val a = book("a", write("a.epub", epubBytes("a")))
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        drive.uploadHold["a"] = gate
        drive.uploadEntered["a"] = entered

        val first = Recorder()
        val holder = arrayOfNulls<UploadPool.Result>(1)
        val runner = thread("pool") { holder[0] = run(listOf(a), first) }
        releasing(gate) {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            // The first transfer is inside the fake, before it has stored anything.
            assertEquals("nothing may have been created yet", 0, drive.creates.get())

            // A second caller (a manual retry, or a sync pass) asks for the same book.
            val second = Recorder()
            val overlapping = run(listOf(a), second)
            assertEquals(listOf("a"), overlapping.skipped.map { it.id })
            assertTrue(overlapping.confirmed.isEmpty())
            assertTrue("the second caller must not touch the badge", second.verified.isEmpty())
            // Skipped, so it sent nothing: still the one transfer, and no second create.
            assertEquals(0, drive.creates.get())
        }
        runner.join(30_000)
        assertNotNull("RUNNER DID NOT RETURN for test 12", holder[0])
        assertTrue("A BOOK FAILED in test 12", holder[0]!!.failed.isEmpty())
        assertEquals("WRONG CONFIRMED SET in test 12", setOf("a"), holder[0]!!.confirmed.keys)
        assertEquals(1, drive.countFor("a"))
        assertEquals(1, drive.creates.get())
        assertFalse("the book must not stay in flight after it finishes", pool.isInFlight("a"))
    }

    /**
     * Runs [body], and opens every gate it was holding even when it fails.
     *
     * Without this a single failed assertion would leave the uploaded books blocked inside the fake
     * transport, and the runner thread would still be holding them when the next test starts.
     */
    private fun releasing(vararg gates: CountDownLatch, body: () -> Unit) {
        try {
            body()
        } finally {
            gates.forEach { it.countDown() }
        }
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(Runnable { body() }, name).apply { isDaemon = true; start() }
}
