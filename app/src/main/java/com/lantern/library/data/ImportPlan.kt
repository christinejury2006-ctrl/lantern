package com.lantern.library.data

/**
 * Choosing several books at once, and what to do with each of them.
 *
 * Importing used to be one file at a time, which on a phone meant opening the picker, choosing a
 * book, waiting, and doing it again — ten times for ten books. The picker can return a list; the
 * rest of this file is about not making a mess of it.
 *
 * Three things have to be true before a file is imported:
 *
 * 1. **It is a book this app can open.** EPUB or PDF, measured from the bytes, not from a
 *    filename that someone could have typed anything into.
 * 2. **It is not already here.** Once by document identity, once by content: a reader who picks
 *    the same file twice from two folders, or picks a book they imported last week, must not end
 *    up with two records, two files and two uploads.
 * 3. **It is not a duplicate of another file in the same selection**, which is the case that used
 *    to slip through because each import ran in isolation.
 *
 * Everything in here is pure. The store does the copying; this decides what is worth copying.
 */

/** One file the reader chose, measured before anything is imported. */
data class ImportCandidate(
    /** Stable document identity (the picker's URI). The same document always has the same one. */
    val uri: String,
    /** The name to show: the file's own display name, not a guess. */
    val name: String,
    /** EPUB or PDF, or null when the file is neither. */
    val format: BookFormat?,
    val bytes: Long,
    /**
     * MD5 of the file's first 64 KiB, or "" when it could not be read.
     *
     * Size alone collides (two 1.4 MB EPUBs are common); a head hash does not, and 64 KiB costs
     * nothing to read. It is a *fingerprint*, not a verification: the real checksum still happens
     * at upload and at restore.
     */
    val headHash: String
) {
    /** What makes two files the same book, independent of where they were picked from. */
    val contentKey: String get() = if (headHash.isBlank() || bytes <= 0L) "" else "$bytes:$headHash"

    val supported: Boolean get() = format == BookFormat.EPUB || format == BookFormat.PDF
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
        get() = items.count { it.verdict is ImportVerdict.DuplicateInSelection || it.verdict is ImportVerdict.DuplicateInLibrary }
    val problemCount: Int get() = items.count { it.verdict is ImportVerdict.Unsupported || it.verdict is ImportVerdict.Unreadable }

    /** "3 of 5 ready" — the summary line above the list. */
    fun summary(): String = when {
        total == 0 -> "No files chosen"
        readyCount == total -> "$total ready to import"
        else -> "$readyCount of $total ready"
    }

    /** Removes one row, recomputing the verdicts so a duplicate becomes ready when its twin goes. */
    fun without(uri: String): ImportPlan = ImportPlanner.planOf(items.filterNot { it.candidate.uri == uri })
}

object ImportPlanner {

    /**
     * Decides every item in [selection].
     *
     * @param findInLibrary asks the library whether these exact bytes are already there. Injected
     *   rather than passed as a list so the store can compare only the books whose size collides,
     *   instead of hashing two hundred files every time the picker closes.
     */
    fun plan(
        selection: List<ImportCandidate>,
        findInLibrary: (ImportCandidate) -> LibraryBook?
    ): ImportPlan {
        val items = ArrayList<ImportItem>(selection.size)
        val seenUris = HashMap<String, String>()
        val seenContent = HashMap<String, String>()

        selection.forEach { candidate ->
            val verdict: ImportVerdict = when {
                candidate.bytes <= 0L -> ImportVerdict.Unreadable("The file is empty.")
                !candidate.supported -> ImportVerdict.Unsupported("Only EPUB and PDF books can be imported.")
                candidate.headHash.isBlank() -> ImportVerdict.Unreadable("The file could not be read.")
                seenUris.containsKey(candidate.uri) ->
                    ImportVerdict.DuplicateInSelection(seenUris.getValue(candidate.uri))
                candidate.contentKey.isNotBlank() && seenContent.containsKey(candidate.contentKey) ->
                    ImportVerdict.DuplicateInSelection(seenContent.getValue(candidate.contentKey))
                else -> findInLibrary(candidate)?.let { ImportVerdict.DuplicateInLibrary(it.title) }
                    ?: ImportVerdict.Ready
            }
            // Only a file that is going to be imported claims its identity, so removing the first
            // copy of a duplicate pair promotes the second one instead of losing both.
            if (verdict is ImportVerdict.Ready) {
                seenUris[candidate.uri] = candidate.name
                if (candidate.contentKey.isNotBlank()) seenContent[candidate.contentKey] = candidate.name
            }
            items += ImportItem(candidate, verdict)
        }
        return ImportPlan(items)
    }

    /** Re-decides an already-built plan, keeping library duplicates as they were. */
    fun planOf(items: List<ImportItem>): ImportPlan {
        val seenUris = HashMap<String, String>()
        val seenContent = HashMap<String, String>()
        val out = ArrayList<ImportItem>(items.size)
        items.forEach { item ->
            val c = item.candidate
            val verdict = when {
                c.bytes <= 0L -> ImportVerdict.Unreadable("The file is empty.")
                !c.supported -> ImportVerdict.Unsupported("Only EPUB and PDF books can be imported.")
                c.headHash.isBlank() -> ImportVerdict.Unreadable("The file could not be read.")
                seenUris.containsKey(c.uri) -> ImportVerdict.DuplicateInSelection(seenUris.getValue(c.uri))
                c.contentKey.isNotBlank() && seenContent.containsKey(c.contentKey) ->
                    ImportVerdict.DuplicateInSelection(seenContent.getValue(c.contentKey))
                // A library duplicate stays a library duplicate: it is a fact about the shelf, not
                // about the order of the selection.
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
    SKIPPED
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
    val message: String = ""
)

/** The counters above the list: "2 of 5 imported". */
data class ImportSummary(
    val total: Int,
    val added: Int,
    val failed: Int,
    val skipped: Int
) {
    val finished: Int get() = added + failed + skipped
    val complete: Boolean get() = total > 0 && finished >= total

    fun line(): String = when {
        total == 0 -> "Nothing to import"
        !complete -> "$added of $total imported · ${total - finished} to go"
        failed > 0 -> "$added imported · $failed failed"
        added == 0 -> "Nothing was imported"
        else -> "$added imported"
    }

    companion object {
        fun of(rows: Collection<ImportProgress>, total: Int = rows.size) = ImportSummary(
            total = total,
            added = rows.count { it.state == ImportState.ADDED },
            failed = rows.count { it.state == ImportState.FAILED },
            skipped = rows.count { it.state == ImportState.SKIPPED }
        )
    }
}
