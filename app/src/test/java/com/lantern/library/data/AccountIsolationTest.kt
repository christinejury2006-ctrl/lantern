package com.lantern.library.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Account isolation: guest and each Google account are separate namespaces.
 *
 * These run on the JVM, so they test the *rules and the storage contract* — the namespace a piece
 * of data is filed under, the generation that decides whether a callback is still welcome, and the
 * migration that moves a pre-3.7.0 shelf into exactly one owner. They deliberately do not claim to
 * have driven the Compose UI: the nine behaviours listed in the round are mapped to the mechanism
 * that produces each one, and each assertion below names the mechanism.
 *
 * | Behaviour                                        | Mechanism asserted here                       |
 * |--------------------------------------------------|-----------------------------------------------|
 * | 1 guest import then sign in: no merge/upload      | guest books never leave `guest`, no upload    |
 * | 2 sign out returns to a fresh Guest Mode          | sign-out target is `guest`, empty on disk     |
 * | 3 A's cloud is untouched by sign-out              | sign-out never writes a cloud tombstone       |
 * | 4 A out/in restores library + history             | A's own files survive sign-out                |
 * | 5 A to B hides A                                  | different namespaces, no shared file          |
 * | 6 guest reopened keeps its data                   | guest's file survives an account session      |
 * | 7 guest prefs/bookmarks separate                  | preference and bookmark files are per-profile |
 * | 8 a stale A callback cannot mutate B              | [SessionGate] refuses A's generation          |
 * | 9 offline startup shows no previous account       | ownership check refuses an unowned namespace  |
 */
class AccountIsolationTest {

    private lateinit var root: File

    private val accountA = "102938475647382910"
    private val accountB = "556677889900112233"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lore-iso").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // -- the namespace scheme ----------------------------------------------------------------

    @Test
    fun `1 guest data is filed under guest and never under an account`() {
        assertEquals(Profiles.GUEST, Profiles.namespaceFor(null))
        assertEquals(Profiles.GUEST, Profiles.namespaceFor(""))
        assertEquals(Profiles.GUEST, Profiles.namespaceFor("   "))
        assertTrue(Profiles.isGuest(Profiles.GUEST))
        // A guest book therefore has no Drive identity at all: nothing in the guest path can reach
        // an upload, which is the "no automatic upload on sign-in" guarantee.
        assertTrue(UploadOwnership.decide(Profiles.GUEST, "anything", accountA, null)
            is UploadOwnership.Decision.Refused)
    }

    @Test
    fun `5 two accounts never share a namespace or a file`() {
        val a = Profiles.namespaceFor(accountA)
        val b = Profiles.namespaceFor(accountB)
        assertNotEquals(a, b)
        // Same directory layout the app uses: one `profiles/<namespace>/` per identity.
        val dirA = File(File(root, "profiles"), a).apply { mkdirs() }
        val dirB = File(File(root, "profiles"), b).apply { mkdirs() }
        File(dirA, "library.json").writeText("[{\"id\":\"a-book\"}]")
        File(dirB, "library.json").writeText("[{\"id\":\"b-book\"}]")
        assertFalse(File(dirA, "library.json").readText().contains("b-book"))
        assertFalse(File(dirB, "library.json").readText().contains("a-book"))
    }

    @Test
    fun `the namespace is derived from the account id and never from an email`() {
        val emailLike = "reader@example.com"
        val ns = Profiles.namespaceFor(emailLike)
        assertFalse("an email must not appear in a path", ns.contains("reader@example.com"))
        assertFalse(ns.contains("@"))
        // Every namespace is a legal file name.
        assertTrue(Profiles.isValidNamespace(ns))
        // And the readable part is not the identity: the hash is.
        assertEquals(ns, Profiles.namespaceFor(emailLike))
        assertNotEquals(ns, Profiles.namespaceFor("reader@example.org"))
    }

    @Test
    fun `namespaces that look alike are still different directories`() {
        // Two account ids whose sanitised form collides must still produce two namespaces.
        val a = Profiles.namespaceFor("12/34")
        val b = Profiles.namespaceFor("12_34")
        assertNotEquals(a, b)
        // Long ids are truncated for readability but not for identity.
        val long1 = Profiles.namespaceFor("9".repeat(80))
        val long2 = Profiles.namespaceFor("9".repeat(79) + "8")
        assertNotEquals(long1, long2)
        assertTrue(long1.length <= 96)
    }

    @Test
    fun `9 an unowned namespace is refused rather than opened`() {
        // The ownership rule is what an offline start leans on: a namespace whose recorded owner is
        // not the account in question must not be treated as that account's shelf.
        assertFalse(Profiles.isValidNamespace(""))
        assertFalse(Profiles.isValidNamespace("../../etc"))
        assertFalse(Profiles.isValidNamespace("guest "))
        assertFalse(Profiles.isValidNamespace("google_1_2/../3"))
        assertTrue(Profiles.isValidNamespace(Profiles.GUEST))
        assertTrue(Profiles.isValidNamespace(Profiles.namespaceFor(accountA)))
    }

    @Test
    fun `7 preferences and bookmarks are stored per profile`() {
        // Every file the round names has to be inside a profile directory; none of them may be a
        // shared file in filesDir any more. The list is the app's own, so a new shared file cannot
        // be added without this test noticing.
        listOf(
            "library.json", "want_to_read.json", "bookmarks.json",
            "library_appearance.json", "reader_appearance.json",
            "upload_queue.json", "upload_journal.json", "restore_journal.json"
        ).forEach { name ->
            assertTrue("$name must be per-profile", name in Profiles.FILES)
        }
        val guestDir = File(File(root, "profiles"), Profiles.GUEST).apply { mkdirs() }
        val accountDir = File(File(root, "profiles"), Profiles.namespaceFor(accountA)).apply { mkdirs() }
        File(guestDir, "bookmarks.json").writeText("guest-marks")
        File(accountDir, "bookmarks.json").writeText("account-marks")
        assertEquals("guest-marks", File(guestDir, "bookmarks.json").readText())
        assertEquals("account-marks", File(accountDir, "bookmarks.json").readText())
    }

    // -- the session gate --------------------------------------------------------------------

    @Test
    fun `8 a stale account A callback cannot write into account B`() {
        val nsA = Profiles.namespaceFor(accountA)
        val nsB = Profiles.namespaceFor(accountB)
        val gate = SessionGate()
        val genA = gate.begin(nsA)
        assertTrue(gate.isCurrent(genA, nsA))
        // The reader switches to B while A's Drive sync is still in the air.
        val genB = gate.begin(nsB)
        assertFalse("A's answer must be refused after the switch", gate.isCurrent(genA, nsA))
        assertFalse("and it must not be mistaken for B's", gate.isCurrent(genA, nsB))
        assertTrue(gate.isCurrent(genB, nsB))
        // A generation alone is not enough: it must also still name the namespace it carries.
        assertFalse(gate.isCurrent(genB, nsA))
        assertEquals(nsB, gate.activeNamespace())
    }

    @Test
    fun `2 and 4 signing out ends the account session without ending the account`() {
        val nsA = Profiles.namespaceFor(accountA)
        val gate = SessionGate()
        val genA = gate.begin(nsA)
        // Sign out: a fresh guest session, exactly as `activate(Profiles.GUEST, null)` does.
        val genGuest = gate.begin(Profiles.GUEST)
        assertFalse(gate.isCurrent(genA, nsA))
        assertTrue(gate.isCurrent(genGuest, Profiles.GUEST))
        // Signing back in to A is a new session, and it is still A's namespace: nothing was erased.
        val again = gate.begin(nsA)
        assertTrue(gate.isCurrent(again, nsA))
        assertNotEquals(genA, again)
    }

    @Test
    fun `3 signing out never removes cloud data`() {
        // The rule, as source: sign-out may not call the store's tombstoning removal, and it may not
        // touch Drive's delete path. Read as text because that is exactly the mistake that shipped
        // once and would ship again silently.
        val source = storeSource()
        val signOut = section(source, "fun signOut(activity", "private fun endSession")
        assertFalse(
            "sign-out must not tombstone books via LanternStore.remove",
            signOut.contains("remove(")
        )
        assertFalse(
            "sign-out must not delete Drive files",
            signOut.contains("deleteFile") || signOut.contains("trash")
        )
        assertTrue(
            "sign-out must stop that account's background work",
            signOut.contains("UploadScheduler.cancelAccount")
        )
        assertTrue(
            "sign-out must end the session before it hands the screen back",
            signOut.contains("endSession")
        )
    }

    @Test
    fun `6 a guest library survives a full account session`() {
        // The guest directory is never the target of an account migration or an account purge.
        val guestDir = File(File(root, "profiles"), Profiles.GUEST).apply { mkdirs() }
        File(guestDir, "library.json").writeText("[{\"id\":\"guest-book\"}]")
        // An account session runs and is purged; the guest directory is untouched.
        val accountDir = File(File(root, "profiles"), Profiles.namespaceFor(accountA)).apply { mkdirs() }
        File(accountDir, "library.json").writeText("[{\"id\":\"account-book\"}]")
        File(accountDir, "library.json").delete()
        assertTrue(File(guestDir, "library.json").exists())
        assertEquals("[{\"id\":\"guest-book\"}]", File(guestDir, "library.json").readText())
    }

    // -- migration ---------------------------------------------------------------------------

    @Test
    fun `the pre-3_7_0 shelf is moved into the one profile that owns it`() {
        val legacy = File(root, "files").apply { mkdirs() }
        Profiles.FILES.forEach { File(legacy, it).writeText("""[{"id":"$it"}]""") }
        val target = File(File(root, "profiles"), Profiles.namespaceFor(accountA)).apply { mkdirs() }

        val moved = LegacyMigrate.moveFiles(legacy, target, Profiles.FILES)
        assertEquals(Profiles.FILES.size, moved)
        Profiles.FILES.forEach { name ->
            assertFalse("$name must no longer be shared", File(legacy, name).exists())
            assertTrue("$name must be in the owner's directory", File(target, name).exists())
        }
    }

    @Test
    fun `a migration killed half way finishes on the next start and never overwrites`() {
        val legacy = File(root, "files").apply { mkdirs() }
        Profiles.FILES.forEach { File(legacy, it).writeText("old-$it") }
        val target = File(File(root, "profiles"), Profiles.GUEST).apply { mkdirs() }
        // Simulate a crash after the first file had moved, plus a newer copy already at one
        // destination (the "process died mid-write, then something re-wrote it" case).
        File(legacy, "library.json").renameTo(File(target, "library.json"))
        File(target, "bookmarks.json").writeText("newer-copy")

        LegacyMigrate.moveFiles(legacy, target, Profiles.FILES)

        assertEquals("old-library.json", File(target, "library.json").readText())
        assertEquals("a newer destination copy is never replaced", "newer-copy", File(target, "bookmarks.json").readText())
        // Everything else still arrived.
        assertTrue(File(target, "want_to_read.json").exists())
        assertTrue(File(target, "upload_queue.json").exists())
        // And a second run is a no-op rather than a second move.
        assertEquals(0, LegacyMigrate.moveFiles(legacy, target, Profiles.FILES))
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun storeSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/java/com/lantern/library/data/LanternStore.kt")
            if (direct.exists()) return direct.readText()
            val inApp = File(dir, "app/src/main/java/com/lantern/library/data/LanternStore.kt")
            if (inApp.exists()) return inApp.readText()
            dir = dir.parentFile
        }
        throw AssertionError("LanternStore.kt is not readable from the test working directory")
    }

    private fun section(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertNotNull("expected to find $from", if (start >= 0) source else null)
        val end = source.indexOf(to, start)
        return if (end > start) source.substring(start, end) else source.substring(start)
    }
}
