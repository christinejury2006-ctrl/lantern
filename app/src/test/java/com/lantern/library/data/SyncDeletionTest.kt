package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deletions across devices.
 *
 * The library merge was a union, and a union cannot express a deletion: "this phone does not have
 * the book" and "the reader deleted the book" looked identical, so every sync put the book back
 * from the account. These tests pin the replacement — a tombstone with a timestamp, which survives
 * a stale snapshot, an offline device, and a merge that has already run twice.
 */
class SyncDeletionTest {

    private fun book(
        id: String,
        title: String = "A Book",
        addedAt: Long = 1_000L,
        page: Int = 0,
        pages: Int = 100,
        lastRead: Long = 0L,
        driveFileId: String? = "drive-$id",
        backupAt: Long = 500L
    ) = LibraryBook(
        id = id,
        title = title,
        author = "An Author",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        pageCount = pages,
        currentPage = page,
        lastReadAt = lastRead,
        addedAt = addedAt,
        driveFileId = driveFileId,
        backupAt = backupAt
    )

    private fun snapshot(
        books: List<LibraryBook>,
        deletions: List<Tombstone> = emptyList(),
        bookmarks: List<SavedBookmark> = emptyList(),
        at: Long = 2_000L
    ) = AccountSnapshot(
        updatedAt = at,
        userId = "user-1",
        books = books,
        bookmarks = bookmarks,
        libraryAppearance = AppearLook.libraryDefault,
        readerAppearance = AppearLook.readerDefault,
        prefs = ReadingPrefs(),
        libraryAppearAt = at,
        readerAppearAt = at,
        prefsAt = at,
        bookOrder = books.map { it.id },
        deletions = deletions
    )

    @Test
    fun `a book deleted on one device stays deleted on the other`() {
        val bookA = book("a")
        val bookB = book("b")
        // Device A still has the snapshot from before the deletion; it deletes "a" and syncs.
        val deviceA = snapshot(listOf(bookB), deletions = listOf(Tombstone("a", 1_500L)))
        // Device B has not synced since, so its own library still holds both books.
        val deviceB = snapshot(listOf(bookA, bookB))

        val mergedOnB = AccountSync.merge(deviceB, deviceA)

        assertEquals(listOf("b"), mergedOnB.books.map { it.id })
        assertEquals(listOf("a"), mergedOnB.deletions.map { it.bookId })
    }

    @Test
    fun `an offline device reconnecting with a stale snapshot does not resurrect the book`() {
        // The device's snapshot is older than the deletion and still lists the book.
        val stale = snapshot(listOf(book("a"), book("b")), at = 900L)
        val fromAccount = snapshot(
            listOf(book("b")),
            deletions = listOf(Tombstone("a", 5_000L)),
            at = 6_000L
        )
        val merged = AccountSync.merge(stale, fromAccount)
        assertEquals(listOf("b"), merged.books.map { it.id })
        // And the tombstone is now on this device too, so the next merge cannot undo it either.
        assertEquals(listOf("a"), merged.deletions.map { it.bookId })
        val again = AccountSync.merge(merged, stale)
        assertEquals(listOf("b"), again.books.map { it.id })
    }

    @Test
    fun `deleting twice is idempotent`() {
        val one = Deletions.merge(listOf(Tombstone("a", 100L)), listOf(Tombstone("a", 100L)))
        assertEquals(1, one.size)
        val twice = AccountSync.merge(
            snapshot(emptyList(), deletions = one),
            snapshot(emptyList(), deletions = one)
        )
        assertEquals(1, twice.deletions.size)
        assertEquals(100L, twice.deletions.single().deletedAt)
    }

    @Test
    fun `a later deletion wins over an earlier one`() {
        val merged = Deletions.merge(listOf(Tombstone("a", 100L)), listOf(Tombstone("a", 900L)))
        assertEquals(900L, merged.single().deletedAt)
    }

    @Test
    fun `a book the reader re-imported after deleting it is not shadowed by the tombstone`() {
        // Deleted at t=1000; imported again at t=2000. The new copy is the reader's decision too.
        val reAdded = book("a", addedAt = 2_000L)
        val tombstoned = snapshot(emptyList(), deletions = listOf(Tombstone("a", 1_000L)))
        val merged = AccountSync.merge(snapshot(listOf(reAdded)), tombstoned)
        assertEquals(listOf("a"), merged.books.map { it.id })
    }

    @Test
    fun `a deletion takes the book's bookmarks and leaves the rest of the library alone`() {
        val keep = book("b", page = 42, lastRead = 9_000L)
        val local = snapshot(
            listOf(book("a"), keep),
            bookmarks = listOf(SavedBookmark("a", 3, 10L), SavedBookmark("b", 7, 20L))
        )
        val cloud = snapshot(
            listOf(book("a"), keep),
            bookmarks = listOf(SavedBookmark("a", 3, 10L), SavedBookmark("b", 7, 20L)),
            deletions = listOf(Tombstone("a", 5_000L))
        )
        val merged = AccountSync.merge(local, cloud)
        assertEquals(listOf("b"), merged.books.map { it.id })
        assertEquals(listOf("b"), merged.bookmarks.map { it.bookId }.distinct())
        // Reading progress of an unrelated book is untouched.
        val survivor = merged.books.single()
        assertEquals(42, survivor.currentPage)
        assertEquals(9_000L, survivor.lastReadAt)
    }

    @Test
    fun `a smaller local library is never read as permission to delete everything else`() {
        // No tombstones anywhere: a phone with one book must not remove the account's four.
        val phone = snapshot(listOf(book("a")))
        val account = snapshot(listOf(book("a"), book("b"), book("c"), book("d")))
        val merged = AccountSync.merge(phone, account)
        assertEquals(listOf("a", "b", "c", "d"), merged.books.map { it.id })
        assertTrue(merged.deletions.isEmpty())
    }

    @Test
    fun `two devices deleting different books both win`() {
        val a = snapshot(listOf(book("c")), deletions = listOf(Tombstone("a", 1_000L)))
        val b = snapshot(listOf(book("c"), book("a")), deletions = listOf(Tombstone("b", 1_100L)))
        val merged = AccountSync.merge(a, b)
        assertEquals(listOf("c"), merged.books.map { it.id })
        assertEquals(setOf("a", "b"), merged.deletions.map { it.bookId }.toSet())
    }

    @Test
    fun `a tombstone expires so the list cannot grow forever`() {
        val old = Tombstone("a", System.currentTimeMillis() - Deletions.RETENTION_MS - 1_000L)
        val fresh = Tombstone("b", System.currentTimeMillis())
        val pruned = Deletions.prune(listOf(old, fresh))
        assertEquals(listOf("b"), pruned.map { it.bookId })
    }

    @Test
    fun `a cloud record whose file is missing is a restore, not a backup`() {
        val recordOnly = book("a", driveFileId = "drive-a", backupAt = 500L)
            .copy(filePath = null)
        assertEquals(BookBackupState.RESTORE_REQUIRED, recordOnly.backupState)
        assertEquals(BackupBadge.RESTORE_REQUIRED, BackupAudit.badge(recordOnly, uploading = false))
        // It is not counted as verified, so the summary cannot claim the library is safe.
        val counts = BackupAudit.counts(listOf(recordOnly), uploading = emptySet())
        assertEquals(0, counts.verified)
        assertEquals(1, counts.restoreRequired)
        assertFalse(counts.complete)
    }

    @Test
    fun `a verified book with its local file is the only thing called backed up`() {
        val file = java.io.File.createTempFile("lore-test", ".epub")
        try {
            file.writeBytes(ByteArray(512))
            val onDisk = book("a").copy(filePath = file.absolutePath)
            assertEquals(BackupBadge.VERIFIED, BackupAudit.badge(onDisk, uploading = false))
            assertTrue(BackupRules.restorable(onDisk))

            // Same record, file gone: now it is a restore, and still restorable from the account.
            val withoutFile = onDisk.copy(filePath = null)
            assertEquals(BackupBadge.RESTORE_REQUIRED, BackupAudit.badge(withoutFile, uploading = false))
            assertTrue(BackupRules.restorable(withoutFile))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `offline a queued upload reads as waiting, not as a failure`() {
        val queued = book("a").copy(pendingUpload = true, backupFailed = true, backupAt = 0L)
        assertEquals(BackupBadge.FAILED, BackupAudit.badge(queued, uploading = false, online = true))
        assertEquals(BackupBadge.WAITING, BackupAudit.badge(queued, uploading = false, online = false))
        assertEquals(BackupBadge.UPLOADING, BackupAudit.badge(queued, uploading = true, online = true))
    }

    @Test
    fun `two syncs merging the same pair in either order agree`() {
        // Concurrency is serialized by the app, but convergence must not depend on that: whoever
        // merges first, both devices must end up with the same library.
        val a = snapshot(listOf(book("a", page = 10, lastRead = 500L), book("b")), at = 1_000L)
        val b = snapshot(
            listOf(book("a", page = 20, lastRead = 900L), book("c")),
            deletions = listOf(Tombstone("b", 800L)),
            at = 1_200L
        )
        val ab = AccountSync.merge(a, b)
        val ba = AccountSync.merge(b, a)
        assertEquals(ab.books.map { it.id }, ba.books.map { it.id })
        assertEquals(ab.deletions.map { it.bookId }, ba.deletions.map { it.bookId })
        // The newest reading position survives, from either direction.
        assertEquals(20, ab.books.first { it.id == "a" }.currentPage)
        assertEquals(20, ba.books.first { it.id == "a" }.currentPage)
        assertFalse(ab.books.any { it.id == "b" })
    }

    @Test
    fun `deletions and verified sizes survive the snapshot round trip`() {
        val original = snapshot(
            listOf(book("a").copy(backupBytes = 123_456L)),
            deletions = listOf(Tombstone("gone", 4_000L))
        )
        val json = AccountSync.toJson(original)
        val readBack = AccountSync.fromJson(json)
        assertTrue("the snapshot must parse", readBack != null)
        assertEquals(4_000L, readBack!!.deletions.single().deletedAt)
        assertEquals("gone", readBack.deletions.single().bookId)
        assertEquals(123_456L, readBack.books.single().backupBytes)
        // A v1 snapshot (no deletions, no sizes) still loads: it simply has neither.
        val v1 = """{"schema":1,"updatedAt":1,"accountId":"user-1","books":[]}"""
        val old = AccountSync.fromJson(v1)
        assertTrue(old != null)
        assertTrue(old!!.deletions.isEmpty())
    }

    @Test
    fun `a changed local file is re-sent instead of being trusted from an old stamp`() {
        val confirmed = book("a").copy(backupAt = 500L, backupBytes = 1_000L)
        val file = java.io.File.createTempFile("lore-test", ".epub")
        try {
            file.writeBytes(ByteArray(2_000))
            val changed = confirmed.copy(filePath = file.absolutePath)
            assertTrue(BackupRules.changedSinceBackup(changed, localFileExists = true))
            assertEquals(UploadDecision.SEND, BackupRules.decide(changed, localFileExists = true))

            val same = confirmed.copy(filePath = file.absolutePath, backupBytes = 2_000L)
            assertFalse(BackupRules.changedSinceBackup(same, localFileExists = true))
            assertEquals(UploadDecision.ALREADY_VERIFIED, BackupRules.decide(same, localFileExists = true))
        } finally {
            file.delete()
        }
    }
}
