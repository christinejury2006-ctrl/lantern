package com.lantern.library.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/**
 * Who a piece of Lore data belongs to.
 *
 * Lore is account-based in the way Play is: what the app shows is the library of the identity that
 * is currently active. There are exactly two kinds of identity, and they never share a shelf:
 *
 * - **Guest**, for somebody using the app without signing in. Their books, progress, bookmarks and
 *   preferences live under [GUEST] and stay on this phone.
 * - **A Google account**, identified by its stable account id — the same number Google returns for
 *   that account on every device — never by the email address. The namespace is derived from the id
 *   alone.
 *
 * ## Why the namespace carries a hash
 *
 * The readable part of a namespace (`google_1029384756...`) is there so a bug report can be read,
 * but the identity of the namespace is the hash suffix. Two different account ids that happen to
 * normalise to the same readable text — or an id containing a character some file system dislikes —
 * still get two different directories, because the suffix is derived from the raw id. That is what
 * "collision-resistant" means here: a collision requires an MD5 prefix collision on the account id,
 * not a coincidence in a sanitised string.
 *
 * Nothing in this file touches the disk; [ProfileStore] does that. Keeping it pure is what lets the
 * namespace rules be tested without Android.
 */
object Profiles {

    /** The namespace of an unsigned-in reader. A plain word, because it is also a directory name. */
    const val GUEST = "guest"

    /** Longest acceptable namespace we will act on. Bounded so a corrupt job cannot become a path. */
    private const val MAX_NAMESPACE_LENGTH = 96

    /**
     * The per-reader files that moved under `profiles/` in 3.7.0.
     *
     * Public so the migration and its test agree on the list: a file that is forgotten here would
     * stay shared between accounts, which is the one thing this round is about.
     */
    val FILES = listOf(
        "library.json",
        "want_to_read.json",
        "bookmarks.json",
        "library_appearance.json",
        "reader_appearance.json",
        "upload_queue.json",
        "upload_journal.json",
        "restore_journal.json",
        "deletions.json",
        "pending_deletes.json"
    )

    /**
     * The namespace that owns data for [accountId].
     *
     * A blank or missing account id is **guest**, never a shared "unknown account": data without a
     * proven owner must not be filed under somebody's account.
     */
    fun namespaceFor(accountId: String?): String {
        val id = accountId?.trim().orEmpty()
        if (id.isEmpty()) return GUEST
        return "google_${readable(id)}_${hash(id)}"
    }

    /** True for the guest namespace. */
    fun isGuest(namespace: String): Boolean = namespace == GUEST

    /**
     * Whether [namespace] is one this app could have produced.
     *
     * Background work carries its namespace in its input data, which means it comes back through a
     * database rather than a function call. A malformed value must be refused before it is turned
     * into a file path, so this checks both the shape and the alphabet.
     */
    fun isValidNamespace(namespace: String): Boolean {
        if (namespace.isEmpty() || namespace.length > MAX_NAMESPACE_LENGTH) return false
        if (namespace == GUEST) return true
        if (!namespace.startsWith("google_")) return false
        return namespace.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    /** The account id inside an `owner` record, or null for guest. Used for ownership checks. */
    fun namespaceOf(ownerAccountId: String?): String = namespaceFor(ownerAccountId)

    /**
     * A readable middle section for a namespace: letters, digits, `_` and `-` only.
     *
     * Kept short (24 characters) so the whole directory name stays well inside file-system limits
     * even with the hash appended.
     */
    private fun readable(accountId: String): String {
        val cleaned = accountId.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return cleaned.take(24).ifBlank { "account" }
    }

    /** First eight hex characters of the SHA-256 of the raw account id. */
    private fun hash(accountId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(accountId.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it) }
}

/** What a stored profile says about itself. Written next to the profile's data, in `profile.json`. */
data class ProfileIdentity(
    val namespace: String,
    /** The stable Google account id this profile belongs to, or null for guest. */
    val ownerAccountId: String?,
    val createdAt: Long
) {
    fun asJson(): String = org.json.JSONObject()
        .put("namespace", namespace)
        .put("ownerAccountId", ownerAccountId ?: "")
        .put("createdAt", createdAt)
        .toString()

    companion object {
        fun from(raw: String?): ProfileIdentity? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = org.json.JSONObject(raw)
                ProfileIdentity(
                    namespace = json.optString("namespace"),
                    ownerAccountId = json.optString("ownerAccountId").ifBlank { null },
                    createdAt = json.optLong("createdAt", 0L)
                )
            }.getOrNull()?.takeIf { Profiles.isValidNamespace(it.namespace) }
        }
    }
}

/**
 * The on-disk side of account isolation: one directory and one preferences file per identity.
 *
 * ```
 * filesDir/profiles/guest/library.json
 * filesDir/profiles/google_1029384756_ab12cd34/library.json
 * ```
 *
 * Every per-reader file the app keeps moves under this root: the library, "want to read", bookmarks,
 * both appearance files, the upload queue and both transfer journals, and the deletion records. The
 * book *bytes* do not: they live in `externalFilesDir/books/<id>.epub`, keyed by an id that is only
 * reachable through a namespace's own library.json, so one account cannot see another's books even
 * though the files sit side by side. Moving hundreds of megabytes per account switch would be a
 * slow, dangerous operation for no privacy gain.
 *
 * ## The migration from the single shared shelf
 *
 * Every version before 3.7.0 wrote those files directly into `filesDir`, with the signed-in account
 * recorded only in preferences. That data has exactly one correct owner:
 *
 * - signed in, with a stable account id on file → that account's namespace;
 * - otherwise → guest.
 *
 * The move is a rename per file, and the marker is written **last**. If the process dies half way,
 * the next start finds no marker, and each file that has already moved is simply no longer at the
 * old path, so the remaining ones still move. Once a file exists at the destination it is never
 * overwritten by the migration: a partially migrated install cannot lose the newer copy.
 */
class ProfileStore(private val app: Application) {

    private val root = File(app.filesDir, "profiles")

    private val legacyPrefs: SharedPreferences
        get() = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    /** Files that used to live directly in `filesDir` and now belong to the active profile. */
    private val managedFiles = Profiles.FILES

    fun dir(namespace: String): File =
        File(root, namespace).apply { mkdirs() }

    /**
     * A file inside a profile.
     *
     * Deliberately does not create the directory: the app asks for these paths on nearly every
     * write and read, and a `mkdirs()` syscall per call would be paid on the main thread. [dir] and
     * [ensure] create the directory once, when an identity is opened.
     */
    fun file(namespace: String, name: String): File = File(File(root, namespace), name)

    fun preferences(namespace: String): SharedPreferences =
        app.getSharedPreferences(prefsName(namespace), Context.MODE_PRIVATE)

    private fun prefsName(namespace: String): String = ProfileStore.preferencesName(namespace)

    /** The identity written into a profile, or null when the profile has never been created. */
    fun identity(namespace: String): ProfileIdentity? {
        val file = file(namespace, PROFILE_FILE)
        if (!file.exists()) return null
        return ProfileIdentity.from(runCatching { file.readText() }.getOrNull())
    }

    /**
     * Creates the profile if it does not exist, and returns its identity.
     *
     * An existing profile is never rewritten with a different owner. That is the check that stops
     * account A's directory from being adopted by account B, even if a namespace string were ever
     * computed wrongly.
     */
    fun ensure(namespace: String, ownerAccountId: String?): ProfileIdentity {
        val existing = identity(namespace)
        if (existing != null) return existing
        val created = ProfileIdentity(namespace, ownerAccountId, System.currentTimeMillis())
        dir(namespace)
        runCatching { file(namespace, PROFILE_FILE).writeText(created.asJson()) }
        return created
    }

    /**
     * Whether [accountId] may read and write [namespace].
     *
     * Guest is owned by nobody and is reachable by nobody but a signed-out app. A Google namespace
     * is reachable only by the account id recorded in its `profile.json`.
     */
    fun ownedBy(namespace: String, accountId: String?): Boolean {
        if (!Profiles.isValidNamespace(namespace)) return false
        if (namespace == Profiles.GUEST) return accountId.isNullOrBlank()
        val owner = identity(namespace)?.ownerAccountId ?: return false
        return !accountId.isNullOrBlank() && owner == accountId
    }

    /**
     * Deletes one profile: its directory and its preferences.
     *
     * **Never called by the app.** Sign-out keeps the account's directory, because it holds work the
     * account still owes (the transfer queue) and books that were never confirmed in the cloud.
     * This exists so that a deliberate, explicit "forget this account on this phone" action has an
     * implementation, and so that a test can prove the guest directory is never the target of it.
     */
    fun purge(namespace: String): Boolean {
        if (namespace == Profiles.GUEST) return false
        if (!Profiles.isValidNamespace(namespace)) return false
        val removed = File(root, namespace).deleteRecursively()
        preferences(namespace).edit().clear().apply()
        return removed
    }

    /** Every namespace directory that exists, for the diagnostics screen and for tests. */
    fun namespaces(): List<String> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && Profiles.isValidNamespace(it.name) }
        .map { it.name }
        .sorted()

    /**
     * Moves the pre-3.7.0 shared files into the profile that owns them. Idempotent and crash-safe.
     *
     * @return the namespace the legacy data was moved into, or null when there was nothing to move
     *   (a fresh install, or a migration this device already completed).
     */
    fun migrateLegacyIfNeeded(): String? {
        val marker = File(root, LEGACY_MARKER)
        if (marker.exists()) return null
        root.mkdirs()
        val ownerAccountId = legacyOwnerAccountId()
        val target = Profiles.namespaceFor(ownerAccountId)
        ensure(target, ownerAccountId)
        dir(target)
        LegacyMigrate.moveFiles(app.filesDir, dir(target), managedFiles)
        migrateLegacyPreferences(target)
        // Last, so a crash above leaves the migration to be completed rather than declared done.
        runCatching { marker.writeText(target) }
        return target
    }

    private fun legacyOwnerAccountId(): String? {
        val signedIn = legacyPrefs.getBoolean("in", false)
        val uid = legacyPrefs.getString("uid", "").orEmpty().trim()
        if (!signedIn || uid.isEmpty() || '@' in uid) return null
        return uid
    }

    /**
     * Copies the old single preferences file into [target]'s own preferences.
     *
     * Copied rather than moved: the legacy file is left in place, so if anything about the migration
     * turns out to be wrong the original evidence is still on the device. Nothing reads it again.
     */
    private fun migrateLegacyPreferences(target: String) {
        val all = runCatching { legacyPrefs.all }.getOrDefault(emptyMap())
        if (all.isEmpty()) return
        val next = preferences(target)
        val editor = next.edit()
        all.forEach { (key, value) ->
            if (key == "uid" || key == "in" || key == "name" || key == "email" || key == "prov") return@forEach
            if (next.contains(key)) return@forEach
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    val strings = value.filterIsInstance<String>().toSet()
                    editor.putStringSet(key, strings)
                }
            }
        }
        editor.apply()
    }

    companion object {
        const val PROFILE_FILE = "profile.json"
        const val SESSION_FILE = "session.json"

        /**
         * The namespace the previous run ended in, for callers that must pick a look before the
         * store exists (the splash). Never trusted for data: only for choosing which preferences to
         * read first.
         */
        fun lastNamespace(context: Context): String? = runCatching {
            val file = File(context.filesDir, SESSION_FILE)
            if (!file.exists()) return@runCatching null
            org.json.JSONObject(file.readText())
                .optString("namespace")
                .takeIf { Profiles.isValidNamespace(it) }
        }.getOrNull()

        /** The preferences file name for [namespace]. */
        fun preferencesName(namespace: String): String =
            if (namespace == Profiles.GUEST) "lantern_guest" else "lantern_$namespace"
        private const val LEGACY_MARKER = ".legacy-migrated"
        private const val LEGACY_PREFS = "lantern"
    }
}

/**
 * The file-moving half of the pre-3.7.0 migration, with no Android in it.
 *
 * Split out so the property that matters — *a half-finished migration finishes on the next start
 * and never overwrites a newer copy* — can be tested on the JVM by killing the move half way,
 * rather than only being reasoned about.
 */
object LegacyMigrate {

    /**
     * Moves each of [names] from [from] into [to].
     *
     * A file that already exists at the destination is left alone: after a crash mid-migration the
     * destination may already hold the good copy, and overwriting it with the older source would be
     * exactly the data loss the migration exists to avoid. A rename is preferred; a copy-then-delete
     * covers the case where the two are on different file systems.
     *
     * @return how many files this call moved.
     */
    fun moveFiles(from: File, to: File, names: List<String>): Int {
        var moved = 0
        names.forEach { name ->
            val source = File(from, name)
            val target = File(to, name)
            if (!source.exists()) return@forEach
            if (target.exists()) return@forEach
            if (source.renameTo(target)) {
                moved += 1
            } else {
                val copied = runCatching { source.copyTo(target, overwrite = false) }.isSuccess
                if (copied) {
                    runCatching { source.delete() }
                    moved += 1
                }
            }
        }
        return moved
    }
}

/**
 * Which session the app is in, and whether an asynchronous answer still belongs to it.
 *
 * Every account transition — sign in, sign out, switch account — calls [begin] and gets a new
 * generation. Anything that was in flight before (a Drive sync, a snapshot merge, an upload that
 * reported back) carries the generation it started under, and [isCurrent] tells it whether the app
 * is still in that session.
 *
 * This is the mechanism behind "a delayed callback from Account A must never repopulate Account B's
 * library". It is deliberately a plain object with no Android dependencies: the rule is important
 * enough to be tested on the JVM, and a test that needs an emulator is a test that gets skipped.
 */
class SessionGate {

    private var generation: Int = 0
    private var namespace: String = Profiles.GUEST

    /** Starts a new session for [namespace] and returns its generation. */
    @Synchronized
    fun begin(namespace: String): Int {
        require(Profiles.isValidNamespace(namespace)) { "not a namespace: $namespace" }
        this.namespace = namespace
        generation += 1
        return generation
    }

    /** The generation of the current session. */
    @Synchronized
    fun current(): Int = generation

    /** The namespace of the current session. */
    @Synchronized
    fun activeNamespace(): String = namespace

    /** True when [gen] is still the live session. */
    @Synchronized
    fun isCurrent(gen: Int): Boolean = gen == generation

    /**
     * True when [gen] is live **and** still points at [namespace].
     *
     * The second half matters because a generation can be current while a worker holds a namespace
     * from a job that was queued two sessions ago; both must agree before anything is written.
     */
    @Synchronized
    fun isCurrent(gen: Int, namespace: String): Boolean =
        gen == generation && namespace == this.namespace
}
