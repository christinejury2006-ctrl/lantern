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
 * And one more, learned from a two-hundred-book batch: **it must fit on the shelf.** The library has
 * a maximum size; discovering that *after* copying 200 files is a waste of the reader's battery and
 * storage, so capacity is decided in the plan, before anything is copied.
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

    /**
     * The shelf is full.
     *
     * [remaining] is how many books still fit, so the sheet can say "3 of these will not fit"
     * before a single byte is copied, instead of failing file after file at the end of a long run.
     */
    data class LibraryFull(val remaining: Int) : ImportVerdict()
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
        is ImportVerdict.LibraryFull ->
            if (v.remaining <= 0) "Not imported: your library is at its limit."
            else "Not imported: only ${v.remaining} more books fit."
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
    val overflowCount: Int get() = items.count { it.verdict is ImportVerdict.LibraryFull }

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
     * @param capacity how many more user books the shelf can hold. `Int.MAX_VALUE` for "no limit".
     * @param findInLibrary asks the library whether these exact bytes are already there. Injected
     *   rather than passed as a list so the store can compare only the books whose size collides,
     *   instead of hashing two hundred files every time the picker closes.
     */
    fun plan(
        selection: List<ImportCandidate>,
        capacity: Int = Int.MAX_VALUE,
        findInLibrary: (ImportCandidate) -> LibraryBook? = { null }
    ): ImportPlan {
        val items = ArrayList<ImportItem>(selection.size)
        val seenUris = HashMap<String, String>()
        val seenContent = HashMap<String, String>()
        var remaining = capacity.coerceAtLeast(0)

        selection.forEach { candidate ->
            val verdict: ImportVerdict = when {
                candidate.bytes <= 0L -> ImportVerdict.Unreadable("The file is empty.")
                !candidate.supported -> ImportVerdict.Unsupported("Only EPUB and PDF books can be imported.")
                !candidate.fingerprint.readable -> ImportVerdict.Unreadable("The file could not be read.")
                seenUris.containsKey(candidate.uri) ->
                    ImportVerdict.DuplicateInSelection(seenUris.getValue(candidate.uri))
                // A fingerprint that cannot prove two files identical is not used as evidence, so a
                // book is never refused because of a length the provider would not confirm.
                candidate.contentKey.isNotBlank() && seenContent.containsKey(candidate.contentKey) ->
                    ImportVerdict.DuplicateInSelection(seenContent.getValue(candidate.contentKey))
                else -> findInLibrary(candidate)?.let { ImportVerdict.DuplicateInLibrary(it.title) }
                    ?: if (remaining > 0) ImportVerdict.Ready else ImportVerdict.LibraryFull(remaining)
            }
            // Only a file that is going to be imported claims its identity and a place on the
            // shelf, so removing the first copy of a duplicate pair promotes the second one instead
            // of losing both.
            if (verdict is ImportVerdict.Ready) {
                seenUris[candidate.uri] = candidate.name
                if (candidate.contentKey.isNotBlank()) seenContent[candidate.contentKey] = candidate.name
                remaining--
            }
            items += ImportItem(candidate, verdict)
        }
        return ImportPlan(items)
    }

    /**
     * Re-decides an already-built plan, keeping library and capacity verdicts as they were.
     *
     * Removing a row must not turn "already on the shelf" into "ready" (that is a fact about the
     * library, not about the selection), and must not invent capacity that the shelf does not have.
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
                // Facts about the shelf and about its capacity survive a row being removed.
                item.verdict is ImportVerdict.DuplicateInLibrary -> item.verdict
                item.verdict is ImportVerdict.LibraryFull -> item.verdict
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
