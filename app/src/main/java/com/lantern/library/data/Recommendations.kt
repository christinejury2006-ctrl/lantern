package com.lantern.library.data

/**
 * Which of the reader's own books to recommend.
 *
 * There is no server-side recommender here and there does not need to be one: the useful
 * question is only "what on this shelf should I open next". So a pick is, in order of
 * importance:
 *
 *  1. a book on the shelf that has not been started at all — recommending a book someone is
 *     halfway through is not a recommendation, it is a nag;
 *  2. in the same category as the book they read most recently, because that is the only signal
 *     of taste this app actually has;
 *  3. the most recently added of those, so a fresh import shows up rather than a dusty one.
 *
 * The result is a small set of ids, deterministic for a given library so the brass ribbon on a
 * spine does not move every time the screen recomposes.
 */
object Recommendations {

    /** How many books get a ribbon. Enough to be visible, few enough to mean something. */
    const val PICK_COUNT = 3

    /**
     * Ids of the books to mark as picked for the reader, at most [count].
     *
     * Returns an empty set when there is nothing unread to recommend, which is the honest
     * answer and leaves the case unmarked rather than inventing a pick.
     */
    fun picks(books: List<LibraryBook>, count: Int = PICK_COUNT): Set<String> {
        if (count <= 0) return emptySet()
        val unread = books.filter { it.shelf == Shelf.TO_READ }
        if (unread.isEmpty()) return emptySet()

        // The category of the most recently read book, if anything has been read at all.
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
