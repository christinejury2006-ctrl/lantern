package com.lantern.library.data

/**
 * Choosing several books at once, and what to do with each of them.
 *
 * Importing used to be one file at a time, which on a phone meant opening the picker, choosing a
 * book, waiting, and doing it again — ten times for ten books. The picker can return a list; the
 * rest of this file is about not making a mess of it at two hundred.
 *
 * Three things have to be true before a file is imported:
 *
 * 1. **It is a book this app can open.** EPUB or PDF, decided from the bytes, not from a filename
 *    someone could have typed anything into.
 * 2. **It is not already here.** Once by document identity, once by content: a reader who picks the
 *    same file twice from two folders, or picks a book they imported last week, must not end up
 *    with two records, two files and two uploads.
 * 3. **It is not a duplicate of another file in the same selection**, which is the case that used to
 *    slip through because each import ran in isolation.
 *
 * There is deliberately **no fourth rule about how many books fit**. The library has no ceiling: the
 * only real limits are the phone's free space and the reader's account quota, and both are reported
 * per file while copying rather than as a verdict here. A planner that knew a maximum number of
 * books would also be the place where that maximum was quietly reintroduced.
 *
 * Everything in here is pure and cheap. It holds metadata only — a URI, a name, a format, a
 * fingerprint — never file contents, and the selection's total memory is a few hundred bytes per
 * book regardless of how large the books are. The store does the copying; this decides what is worth
 * copying.
 */

/** One file the reader chose, measured before anything is imported. */
data class ImportCandidate(
    /** Stable document identity (the picker's URI). The same document always has the same one. */
    val uri: String,
    /** The name to show: the file's own display name, not a guess. */
    val name: String,
    /** EPUB or PDF, or null when the file is neither. */
    val format: BookFormat?,
    /** Size, head hash and tail hash. Bounded work and bounded memory to produce. */
    val fingerprint: ImportFingerprint = ImportFingerprint.UNREADABLE
) {
    /** The file's length in bytes, or 0 when it could not be measured. */
    val bytes: Long get() = fingerprint.bytes

    val supported: Boolean get() = format == BookFormat.EPUB || format == BookFormat.PDF

    /** What makes two files the same book, independent of where they were picked from. */
    val contentKey: String get() = fingerprint.key
}

/** Why an item is or is not going to be imported. */
sealed class ImportVerdict {
    /** Ready to copy into the library. */
    object Ready : ImportVerdict()

    /** The same document, or the same bytes, appears earlier in this selection. */
    data class DuplicateInSelection(val earlierName: String) : ImportVerdict()

    /** A book with these bytes is already on the shelf. */
    data class DuplicateInLibrary(val title: String) : ImportVerdict()

    /** Not an EPUB or a PDF. */
    data class Unsupported(val reason: String) : ImportVerdict()

    /** Could not be opened or measured: a revoked permission, a vanished file, an empty one. */
    data class Unreadable(val reason: String) : ImportVerdict()
}

/** One row of the review sheet. */
data class ImportItem(
    val candidate: ImportCandidate,
    val verdict: ImportVerdict
) {
    val importable: Boolean get() = verdict is ImportVerdict.Ready

    /** The line under the file name in the review sheet. */
    fun note(): String = when (val v = verdict) {
        ImportVerdict.Ready -> "${Transfers.size(candidate.bytes)} · ready to import"
        is ImportVerdict.DuplicateInSelection -> "Already chosen above (same as \u201C${v.earlierName}\u201D)"
        is ImportVerdict.DuplicateInLibrary -> "Already in your library: \u201C${v.title}\u201D"
        is ImportVerdict.Unsupported -> v.reason
        is ImportVerdict.Unreadable -> v.reason
    }
}

/** The whole selection, decided before a single byte is copied. */
data class ImportPlan(val items: List<ImportItem>) {
    val total: Int get() = items.size
    val ready: List<ImportItem> get() = items.filter { it.importable }
    val readyCount: Int get() = ready.size
    val duplicateCount: Int
        get() = items.count {
            it.verdict is ImportVerdict.DuplicateInSelection || it.verdict is ImportVerdict.DuplicateInLibrary
        }
    val problemCount: Int
        get() = items.count {
            it.verdict is ImportVerdict.Unsupported || it.verdict is ImportVerdict.Unreadable
        }

    /** "3 of 5 ready" — the summary line above the list. */
    fun summary(): String = when {
        total == 0 -> "No files chosen"
        readyCount == total -> "$total ready to import"
        else -> "$readyCount of $total ready"
    }

    /** Removes one row, recomputing the verdicts so a duplicate becomes ready when its twin goes. */
    fun without(uri: String): ImportPlan = ImportPlanner.planOf(items.filterNot { it.candidate.uri == uri })

    /** Everything that can actually be imported, in selection order. */
    fun readyItems(): List<ImportItem> = items.filter { it.importable }
}

object ImportPlanner {

    /**
     * Decides every item in [selection].
     *
     * @param findInLibrary asks the library whether these exact bytes are already there. Injected
     *   rather than passed as a list so the store can compare only the books whose size collides,
     *   instead of hashing two hundred files every time the picker closes.
     * @param sameContent confirms that two candidates whose fingerprints agree really are the same
     *   book, by streaming both and comparing their full contents. The store always passes it, so
     *   the app never refuses a book on the sampled windows alone: two different files that share a
     *   length, a first 64 KiB and a last 64 KiB are both offered. `null` means "this caller cannot
     *   read bytes" (tests) and keeps the pre-8.1 behaviour of trusting the fingerprint, which is
     *   what makes this planner pure.
     */
    fun plan(
        selection: List<ImportCandidate>,
        findInLibrary: (ImportCandidate) -> LibraryBook? = { null },
        sameContent: ((ImportCandidate, ImportCandidate) -> Boolean)? = null
    ): ImportPlan {
        val items = ArrayList<ImportItem>(selection.size)
        val seenUris = HashMap<String, String>()
        // Fingerprint key -> the candidates already accepted under that key. A key can cover more
        // than one file once full-content confirmation says they differ.
        val seenContent = HashMap<String, MutableList<ImportCandidate>>()

        selection.forEach { candidate ->
            val earlier = candidate.contentKey.takeIf { it.isNotBlank() }?.let(seenContent::get)
            val verdict: ImportVerdict = when {
                candidate.bytes <= 0L -> ImportVerdict.Unreadable("The file is empty.")
                !candidate.supported -> ImportVerdict.Unsupported("Only EPUB and PDF books can be imported.")
                !candidate.fingerprint.readable -> ImportVerdict.Unreadable("The file could not be read.")
                seenUris.containsKey(candidate.uri) ->
                    ImportVerdict.DuplicateInSelection(seenUris.getValue(candidate.uri))
                // The fingerprint match is confirmed against the full contents before it refuses
                // anything; two different books that happen to share their length, their first
                // 64 KiB and their last 64 KiB stay separate books. With no confirmer (tests only)
                // the match stands, which is the pure, pre-8.1 behaviour.
                earlier != null && (sameContent == null ||
                    earlier.firstOrNull { sameContent(it, candidate) } != null) ->
                    ImportVerdict.DuplicateInSelection(earlier.first().name)
                else -> findInLibrary(candidate)?.let { ImportVerdict.DuplicateInLibrary(it.title) }
                    ?: ImportVerdict.Ready
            }
            // Only a file that is going to be imported claims its identity, so removing the first
            // copy of a duplicate pair promotes the second one instead of losing both.
            if (verdict is ImportVerdict.Ready) {
                seenUris[candidate.uri] = candidate.name
                if (candidate.contentKey.isNotBlank()) {
                    seenContent.getOrPut(candidate.contentKey) { ArrayList(1) } += candidate
                }
            }
            items += ImportItem(candidate, verdict)
        }
        return ImportPlan(items)
    }

    /**
     * Re-decides an already-built plan, keeping the library verdicts as they were.
     *
     * Used when the reader removes a row: a duplicate may now be the only copy left in the
     * selection, so it becomes ready. "Already on the shelf" is a fact about the library, not about
     * the selection, and survives the edit. Same-fingerprint matches are taken as duplicates here
     * because they were confirmed with full contents before the sheet was built.
     */
    fun planOf(items: List<ImportItem>): ImportPlan {
        val seenUris = HashMap<String, String>()
        val seenContent = HashMap<String, String>()
        val out = ArrayList<ImportItem>(items.size)
        items.forEach { item ->
            val c = item.candidate
            val verdict = when {
                c.bytes <= 0L -> ImportVerdict.Unreadable("The file is empty.")
                !c.supported -> ImportVerdict.Unsupported("Only EPUB and PDF books can be imported.")
                !c.fingerprint.readable -> ImportVerdict.Unreadable("The file could not be read.")
                seenUris.containsKey(c.uri) -> ImportVerdict.DuplicateInSelection(seenUris.getValue(c.uri))
                c.contentKey.isNotBlank() && seenContent.containsKey(c.contentKey) ->
                    ImportVerdict.DuplicateInSelection(seenContent.getValue(c.contentKey))
                // A fact about the shelf survives a row being removed.
                item.verdict is ImportVerdict.DuplicateInLibrary -> item.verdict
                else -> ImportVerdict.Ready
            }
            if (verdict is ImportVerdict.Ready) {
                seenUris[c.uri] = c.name
                if (c.contentKey.isNotBlank()) seenContent[c.contentKey] = c.name
            }
            out += ImportItem(c, verdict)
        }
        return ImportPlan(out)
    }
}

/** Where one file is in the import run. */
enum class ImportState {
    /** Chosen, waiting for its turn. */
    WAITING,
    /** Being copied and inspected. */
    COPYING,
    /** In the library; its cloud upload is now the upload queue's business. */
    ADDED,
    /** Something went wrong, with a reason and a Retry action. */
    FAILED,
    /** Skipped because it could not be imported at all (duplicate or unsupported). */
    SKIPPED,
    /** The reader stopped the batch before this file's turn. Nothing was copied for it. */
    STOPPED
}

/**
 * One file's import progress, as the confirm sheet shows it.
 *
 * `ADDED` is deliberately **not** "backed up": a file copied onto this phone is not a verified
 * cloud copy, and the row says "Added to library" so nobody reads a green tick as a backup.
 */
data class ImportProgress(
    val uri: String,
    val name: String,
    val state: ImportState,
    val message: String = "",
    /** Position in the selection, so a lazy list can key rows without searching. */
    val index: Int = 0
) {
    val finished: Boolean get() = state == ImportState.ADDED || state == ImportState.FAILED ||
        state == ImportState.SKIPPED || state == ImportState.STOPPED
}

/** The counters above the list: "2 of 5 imported". */
data class ImportSummary(
    val total: Int,
    val added: Int,
    val failed: Int,
    val skipped: Int,
    val stopped: Int = 0
) {
    val finished: Int get() = added + failed + skipped + stopped
    val complete: Boolean get() = total > 0 && finished >= total

    fun line(): String = when {
        total == 0 -> "Nothing to import"
        !complete -> "$added of $total imported · ${total - finished} to go"
        failed > 0 && stopped > 0 -> "$added imported · $failed failed · $stopped not reached"
        failed > 0 -> "$added imported · $failed failed"
        stopped > 0 -> "$added imported · stopped before the rest"
        added == 0 -> "Nothing was imported"
        else -> "$added imported"
    }

    companion object {
        fun of(rows: Collection<ImportProgress>, total: Int = rows.size) = ImportSummary(
            total = total,
            added = rows.count { it.state == ImportState.ADDED },
            failed = rows.count { it.state == ImportState.FAILED },
            skipped = rows.count { it.state == ImportState.SKIPPED },
            stopped = rows.count { it.state == ImportState.STOPPED }
        )
    }
}
