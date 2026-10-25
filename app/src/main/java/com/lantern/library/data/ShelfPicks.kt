package com.lantern.library.data

/**
 * Which of the reader's own books carry a brass ribbon on the revolving case.
 *
 * Deliberately not part of [Recommendations]: that finds books the reader does not own yet, by
 * querying a catalogue. This answers a much smaller question about the shelf they already have,
 * and needs no network, no cache and no clock.
 *
 * The order of preference:
 *
 *  1. a book that has not been started at all — recommending a book someone is halfway through
 *     is not a recommendation, it is a nag;
 *  2. the same category as the book they read most recently, because that is the only signal of
 *     taste this app actually has;
 *  3. the most recently added of those, so a fresh import shows up rather than a dusty one.
 *
 * The result is a small set of ids, deterministic for a given library, so a ribbon does not move
 * from spine to spine every time the screen recomposes.
 */
object ShelfPicks {

    /** How many books get a ribbon. Enough to be visible, few enough to mean something. */
    const val PICK_COUNT = 3

    /**
     * Ids of the books to mark as picked, at most [count].
     *
     * Empty when nothing is unread: that is the honest answer, and it leaves the case unmarked
     * rather than inventing a pick to fill the space.
     */
    fun picks(books: List<LibraryBook>, count: Int = PICK_COUNT): Set<String> {
        if (count <= 0) return emptySet()
        val unread = books.filter { it.shelf == Shelf.TO_READ }
        if (unread.isEmpty()) return emptySet()

        // The category of the book read most recently, if anything has been read at all.
        val anchor = books
            .filter { it.lastReadAt > 0L }
            .maxByOrNull { it.lastReadAt }
            ?.category
            ?.takeIf { it.isNotBlank() }

        return unread
            .sortedWith(
                compareByDescending<LibraryBook> { it.category == anchor }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.title }
            )
            .take(count)
            .map { it.id }
            .toSet()
    }
}
