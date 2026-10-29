package com.lantern.library.data

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

/**
 * Real upload and verification logic, against an in-memory Drive.
 *
 * No Google account and no network: [DriveLibrary] takes its transport from one seam, so this fake
 * plays the server and the tests drive the actual production path — lookup by `loreId`, create or
 * replace, checksum verification, retry with backoff, and the read-back compare.
 *
 * What is *not* tested here, and must not be claimed: Google's own behaviour. Live Drive was not
 * exercised by any of these tests.
 */
class DriveUploadTest {

    /** A tiny but genuinely-shaped EPUB: ZIP magic plus the container marker BookFiles looks for. */
    private fun epubBytes(seed: String): ByteArray {
        val body = StringBuilder()
        body.append("PK\u0003\u0004")
        body.append("META-INF/container.xml")
        while (body.length < 400) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun pdfBytes(seed: String): ByteArray {
        val body = StringBuilder("%PDF-1.7\n")
        while (body.length < 400) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The fake server.
     *
     * It stores exactly what it is sent, answers the same endpoints the real one does, and can be
     * told to drop a call ("no response") or refuse with a status, so the retry and failure rules
     * can be tested as they are, not as they are described.
     */
    private class FakeDrive : DriveLibrary.Transport {
        val files = LinkedHashMap<String, ByteArray>()
        val names = HashMap<String, String>()
        val loreIds = HashMap<String, String>()
        var creates = 0
        var replaces = 0
        /** Call indexes (1-based, counting every call) that must fail to complete. */
        val dropCalls = HashSet<Int>()
        var callCount = 0
        /** The next create is refused with this status, and consumed. */
        var failNextCreateWith: Int? = null
        /** When true, a create stores the file but the response is lost — an interrupted upload. */
        var loseCreateResponse = false

        override fun call(req: Request): DriveLibrary.Raw? {
            callCount++
            if (callCount in dropCalls) return null
            val url = req.url
            val path = url.encodedPath
            return when {
                // files.list?q=...
                path == "/drive/v3/files" && req.method == "GET" -> list(url.toString())
                // uploads
                path == "/upload/drive/v3/files" && req.method == "POST" -> create(req)
                path.startsWith("/upload/drive/v3/files/") && req.method == "PATCH" -> replace(req, path)
                path.startsWith("/drive/v3/files/") && req.method == "GET" -> read(req, path, url.toString())
                path.startsWith("/drive/v3/files/") && req.method == "DELETE" -> {
                    delete(path)
                    DriveLibrary.Raw(204, ByteArray(0))
                }
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
            failNextCreateWith?.let { code ->
                failNextCreateWith = null
                return DriveLibrary.Raw(code, """{"error":{"code":$code}}""".toByteArray())
            }
            creates++
            val body = req.body as MultipartBody
            val parts = body.parts.map { readBytes(it.body) }
            val meta = JSONObject(String(parts[0], Charsets.UTF_8))
            val id = "file-${creates}-${System.nanoTime()}"
            files[id] = parts[1]
            names[id] = meta.optString("name")
            loreIds[id] = meta.optJSONObject("appProperties")?.optString("loreId").orEmpty()
            if (loseCreateResponse) {
                loseCreateResponse = false
                return null
            }
            return DriveLibrary.Raw(
                200,
                JSONObject().put("id", id).put("name", meta.optString("name")).toString().toByteArray()
            )
        }

        private fun replace(req: Request, path: String): DriveLibrary.Raw {
            replaces++
            val id = path.substringAfterLast('/')
            if (!files.containsKey(id)) return DriveLibrary.Raw(404, ByteArray(0))
            files[id] = readBytes(req.body!!)
            return DriveLibrary.Raw(200, JSONObject().put("id", id).toString().toByteArray())
        }

        private fun read(req: Request, path: String, fullUrl: String): DriveLibrary.Raw {
            val id = path.substringAfterLast('/')
            val bytes = files[id] ?: return DriveLibrary.Raw(404, ByteArray(0))
            if (fullUrl.contains("alt=media")) return DriveLibrary.Raw(200, bytes)
            val json = JSONObject()
                .put("id", id)
                .put("size", bytes.size.toString())
                .put("md5Checksum", md5Static(bytes))
            return DriveLibrary.Raw(200, json.toString().toByteArray())
        }

        private fun delete(path: String) {
            val id = path.substringAfterLast('/')
            files.remove(id)
            loreIds.remove(id)
            names.remove(id)
        }

        private fun readBytes(body: RequestBody): ByteArray {
            val buffer = Buffer()
            body.writeTo(buffer)
            return buffer.readByteArray()
        }

        fun md5Static(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(bytes)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private lateinit var drive: FakeDrive
    private lateinit var dir: File

    @Before
    fun setUp() {
        drive = FakeDrive()
        dir = File.createTempFile("lore-uploads", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        DriveLibrary.transport = drive
        // Retries must not actually wait 600 ms × 4 in CI.
        DriveLibrary.sleeper = { }
        DriveLibrary.clearError()
    }

    @After
    fun tearDown() {
        DriveLibrary.resetTransport()
        dir.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun book(format: BookFormat, file: File, id: String = "book-1") = LibraryBook(
        id = id,
        title = "A Book",
        author = "An Author",
        format = format,
        origin = BookOrigin.IMPORT,
        filePath = file.absolutePath
    )

    // 1 ----------------------------------------------------------------------------------------

    @Test
    fun `an EPUB uploads, is verified against the account, and to a byte count`() {
        val bytes = epubBytes("epub")
        val file = write("book.epub", bytes)
        val b = book(BookFormat.EPUB, file)

        val result = DriveLibrary.upload("token", "appDataFolder", b, file)

        assertTrue("expected a verified upload, got $result", result is DriveOutcome.Ok)
        val id = (result as DriveOutcome.Ok).value
        assertEquals(1, drive.files.size)
        assertTrue("the account must hold the exact bytes", drive.files[id]!!.contentEquals(bytes))
        assertEquals(b.id, drive.loreIds[id])
        // Independent confirmation, not the upload's own word for it.
        assertEquals(true, DriveLibrary.remoteMatches("token", id, file))
        assertEquals(bytes.size.toLong(), file.length())
    }

    // 2 ----------------------------------------------------------------------------------------

    @Test
    fun `a PDF uploads and is verified`() {
        val bytes = pdfBytes("pdf")
        val file = write("book.pdf", bytes)
        val result = DriveLibrary.upload("token", "appDataFolder", book(BookFormat.PDF, file), file)
        assertTrue(result is DriveOutcome.Ok)
        val id = (result as DriveOutcome.Ok).value
        assertTrue(drive.files[id]!!.contentEquals(bytes))
        assertEquals("A Book.pdf", drive.names[id])
    }

    // 3 ----------------------------------------------------------------------------------------

    @Test
    fun `a dropped connection is retried and the upload still succeeds`() {
        val bytes = epubBytes("flaky")
        val file = write("flaky.epub", bytes)
        // The lookup works, the create is lost, then everything works again.
        drive.dropCalls += 2

        val result = DriveLibrary.upload("token", "appDataFolder", book(BookFormat.EPUB, file), file)

        assertTrue("a transient failure must not end the upload", result is DriveOutcome.Ok)
        assertEquals(1, drive.files.size)
        assertTrue(drive.callCount > 3)
    }

    // 4 ----------------------------------------------------------------------------------------

    @Test
    fun `a permanent refusal leaves the book local and says exactly why`() {
        val file = write("blocked.epub", epubBytes("blocked"))
        val b = book(BookFormat.EPUB, file)
        drive.failNextCreateWith = 403

        val result = DriveLibrary.upload("token", "appDataFolder", b, file)

        assertTrue(result is DriveOutcome.Failed)
        assertEquals(0, drive.files.size)
        val failure = DriveLibrary.lastFailure
        assertNotNull(failure)
        assertEquals(b.id, failure!!.bookId)
        assertEquals("A Book", failure.title)
        assertEquals("upload", failure.operation)
        assertFalse("a 403 will not improve by being repeated", failure.retryable)
        assertTrue("the reason must name the actual problem", failure.reason.contains("403"))
        // And a 403 must not be retried forever: one attempt, no creates.
        assertEquals(0, drive.creates)
    }

    // 5 ----------------------------------------------------------------------------------------

    @Test
    fun `a retry after a lost response does not create a second Drive file`() {
        val bytes = epubBytes("idempotent")
        val file = write("idem.epub", bytes)
        // The create stores the bytes and then the answer never arrives — the classic interrupted
        // upload on a phone. The retry must find that file and reuse it.
        drive.loseCreateResponse = true

        val result = DriveLibrary.upload("token", "appDataFolder", book(BookFormat.EPUB, file), file)

        assertTrue(result is DriveOutcome.Ok)
        assertEquals("exactly one file for one book", 1, drive.files.size)
        assertEquals(1, drive.creates)
        // The second attempt replaced the media of the file it found rather than creating another.
        assertTrue(drive.replaces >= 1)
    }

    @Test
    fun `uploading the same book twice keeps one file and updates its bytes`() {
        val file = write("twice.epub", epubBytes("first"))
        val b = book(BookFormat.EPUB, file)
        val first = DriveLibrary.upload("token", "appDataFolder", b, file)
        assertTrue(first is DriveOutcome.Ok)

        // The local file is re-exported: different bytes, same book.
        file.writeBytes(epubBytes("second"))
        val second = DriveLibrary.upload("token", "appDataFolder", b, file)

        assertTrue(second is DriveOutcome.Ok)
        assertEquals(1, drive.files.size)
        assertEquals((first as DriveOutcome.Ok).value, (second as DriveOutcome.Ok).value)
        assertTrue(drive.files.values.single().contentEquals(file.readBytes()))
    }

    // 6 ----------------------------------------------------------------------------------------

    @Test
    fun `the metadata snapshot can be written while a book file fails`() {
        val good = write("good.epub", epubBytes("good"))
        val bad = write("bad.epub", epubBytes("bad"))
        val goodBook = book(BookFormat.EPUB, good, id = "good")
        val badBook = book(BookFormat.EPUB, bad, id = "bad")

        val snapshot = AccountSync.toJson(
            AccountSnapshot(
                updatedAt = 1L, userId = "u", books = listOf(goodBook, badBook), bookmarks = emptyList(),
                libraryAppearance = AppearLook.libraryDefault, readerAppearance = AppearLook.readerDefault,
                prefs = ReadingPrefs(), libraryAppearAt = 1L, readerAppearAt = 1L, prefsAt = 1L,
                bookOrder = listOf("good", "bad")
            )
        )
        val written = DriveLibrary.writeJsonByLoreId(
            "token", "appDataFolder", AccountSync.STATE_LORE_ID, AccountSync.STATE_FILE_NAME, snapshot
        )
        assertTrue(written is DriveOutcome.Ok)

        // The account takes the metadata and then refuses one of the two files.
        drive.failNextCreateWith = 403
        val goodResult = DriveLibrary.upload("token", "appDataFolder", goodBook, good)
        val badResult = DriveLibrary.upload("token", "appDataFolder", badBook, bad)

        // A written snapshot proves nothing about the files: one is there, the other is not.
        assertTrue("the refused book must not be reported as backed up", goodResult is DriveOutcome.Failed)
        assertTrue(badResult is DriveOutcome.Ok)
        assertEquals(2, drive.files.size) // the snapshot, plus the one book that uploaded
        assertEquals("good", DriveLibrary.lastFailure?.bookId)
        // And the snapshot is still readable — records without files is exactly the state the app
        // must be able to describe rather than hide.
        val readBack = DriveLibrary.readJsonByLoreId("token", AccountSync.STATE_LORE_ID)
        assertTrue(readBack is DriveOutcome.Ok)
        assertNotNull((readBack as DriveOutcome.Ok).value)
    }

    // 11 ---------------------------------------------------------------------------------------

    @Test
    fun `an interrupted or corrupt download never replaces a valid local file`() {
        val valid = write("existing.epub", epubBytes("fine"))
        val before = valid.readBytes()

        // The account hands back an HTML error page dressed as a file.
        val junk = "<html><body>403 Forbidden</body></html>".toByteArray()
        val id = "junk-file"
        drive.files[id] = junk

        val result = DriveLibrary.download("token", id, valid, BookFormat.EPUB)
        assertTrue(result is DriveOutcome.Failed)
        assertTrue("the reader's book must survive a bad download", valid.readBytes().contentEquals(before))
        assertNull("no half-written file is left behind", File(dir, "existing.epub.part").takeIf { it.exists() })
    }

    @Test
    fun `a download that 404s leaves the local file alone`() {
        val valid = write("existing2.epub", epubBytes("fine"))
        val before = valid.readBytes()
        val result = DriveLibrary.download("token", "does-not-exist", valid, BookFormat.EPUB)
        assertTrue(result is DriveOutcome.Failed)
        assertTrue(valid.readBytes().contentEquals(before))
    }

    @Test
    fun `a verification mismatch is reported instead of being called a backup`() {
        val file = write("mismatch.epub", epubBytes("one"))
        val b = book(BookFormat.EPUB, file)
        // A server that claims success but stores something else every time.
        val lying = object : DriveLibrary.Transport {
            private val real = drive
            override fun call(req: Request): DriveLibrary.Raw? {
                val raw = real.call(req)
                // Corrupt what the "server" holds right after any write.
                if (req.method == "POST" || req.method == "PATCH") {
                    real.files.keys.toList().forEach { if (it.startsWith("file-")) real.files[it] = "tampered".toByteArray() }
                }
                return raw
            }
        }
        DriveLibrary.transport = lying

        val result = DriveLibrary.upload("token", "appDataFolder", b, file)

        assertTrue("bytes that do not match must not be confirmed", result is DriveOutcome.Failed)
        assertNotNull(DriveLibrary.lastFailure)
        assertEquals(b.id, DriveLibrary.lastFailure!!.bookId)
    }

    /**
     * The percentage on a card comes from this: bytes counted as they are written, ending exactly
     * at the file's length and never beyond it.
     */
    @Test
    fun `progress is reported for every byte of the file, and never overshoots`() {
        // Big enough to leave Okio's 8 KB buffer more than once, so "more than one report" is a real
        // assertion rather than a property of the buffer size.
        val bytes = epubBytes("progress") + ByteArray(64_000) { 'x'.code.toByte() }
        val file = write("progress.epub", bytes)
        val b = book(BookFormat.EPUB, file)
        val seen = ArrayList<Long>()
        var verifyPhase = 0

        val result = DriveLibrary.upload(
            "token", "appDataFolder", b, file,
            onProgress = { sent, total ->
                assertEquals(file.length(), total)
                seen += sent
            },
            onVerifying = { verifyPhase++ }
        )

        assertTrue(result is DriveOutcome.Ok)
        assertTrue("progress must advance", seen.size > 1)
        assertEquals("monotonic", seen.sorted(), seen)
        assertEquals("the last report is the whole file", file.length(), seen.last())
        assertTrue("no report exceeds the file", seen.max() <= file.length())
        // Verification is reported separately from transfer: the bytes were out first.
        assertEquals(1, verifyPhase)
    }

    @Test
    fun `a zero byte file is rejected instead of being reported as progress`() {
        val file = File(dir, "empty.epub").apply { createNewFile() }
        var reported = 0
        val result = DriveLibrary.upload(
            "token", "appDataFolder", book(BookFormat.EPUB, file), file,
            onProgress = { _, _ -> reported++ }
        )
        assertTrue(result is DriveOutcome.Failed)
        assertEquals("nothing was sent, so nothing is reported", 0, reported)
        assertEquals(0, drive.files.size)
    }

    @Test
    fun `retry delays grow and stop at a sane cap`() {
        assertEquals(600L, RetryPolicy.delayMs(1))
        assertEquals(1_200L, RetryPolicy.delayMs(2))
        assertEquals(2_400L, RetryPolicy.delayMs(3))
        assertEquals(4_800L, RetryPolicy.delayMs(4))
        assertEquals(8_000L, RetryPolicy.delayMs(9))
        assertTrue(RetryPolicy.shouldRetry(1, 0))
        assertTrue(RetryPolicy.shouldRetry(1, 503))
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.MAX_ATTEMPTS, 0))
        assertFalse(RetryPolicy.shouldRetry(1, 403))
        assertEquals(TransferClass.UNAUTHORIZED, RetryPolicy.classify(401))
        assertEquals(TransferClass.PERMANENT, RetryPolicy.classify(403))
        assertEquals(TransferClass.TRANSIENT, RetryPolicy.classify(0))
    }
}
