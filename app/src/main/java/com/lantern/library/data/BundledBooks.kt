package com.lantern.library.data

import com.lantern.library.R

data class StarterSpec(
    val id: String,
    val title: String,
    val author: String,
    val asset: String,
    val coverRes: Int,
    val category: String,
    val synopsis: String,
    val format: BookFormat = BookFormat.EPUB
)

object BundledBooks {
    /** Legacy in-memory tester ids. Never re-inject. */
    val seedIds: Set<String> = setOf("eldoria", "aurora", "ember")

    val starter: List<StarterSpec> = listOf(
        StarterSpec(
            id = "starter_pride",
            title = "Pride and Prejudice",
            author = "Jane Austen",
            asset = "starter/pride_and_prejudice.epub",
            coverRes = R.drawable.cover_starter_pride,
            category = "Romance",
            synopsis = "Elizabeth Bennet and Mr Darcy learn to see past pride and first impressions in this classic English romance."
        ),
        StarterSpec(
            id = "starter_goblin",
            title = "The Princess and the Goblin",
            author = "George MacDonald",
            asset = "starter/princess_and_the_goblin.epub",
            coverRes = R.drawable.cover_starter_goblin,
            category = "Romantasy",
            synopsis = "Princess Irene and miner boy Curdie face a goblin kingdom beneath the mountain in an early fairy romance."
        ),
        StarterSpec(
            id = "starter_island",
            title = "Treasure Island",
            author = "Robert Louis Stevenson",
            asset = "starter/treasure_island.epub",
            coverRes = R.drawable.cover_starter_island,
            category = "Adventure",
            synopsis = "Young Jim Hawkins ships out for buried gold and crosses Long John Silver on the high seas."
        ),
        StarterSpec(
            id = "starter_wuthering",
            title = "Wuthering Heights",
            author = "Emily Brontë",
            asset = "starter/wuthering_heights.epub",
            coverRes = R.drawable.cover_starter_wuthering,
            category = "Dark Romance",
            synopsis = "Heathcliff and Catherine’s obsessive love tears through the Yorkshire moors."
        ),
        StarterSpec(
            id = "starter_oz",
            title = "The Wonderful Wizard of Oz",
            author = "L. Frank Baum",
            asset = "starter/wizard_of_oz.epub",
            coverRes = R.drawable.cover_starter_oz,
            category = "Fantasy",
            synopsis = "Dorothy is carried to Oz and walks the yellow brick road toward the Emerald City."
        ),
        StarterSpec(
            id = "starter_alice",
            title = "Alice's Adventures in Wonderland",
            author = "Lewis Carroll",
            asset = "starter/alices_adventures.pdf",
            coverRes = R.drawable.cover_starter_alice,
            category = "Fantasy",
            synopsis = "Alice follows the White Rabbit into Wonderland. Public-domain text set as a PDF for Reader testing.",
            format = BookFormat.PDF
        )
    )

    fun seed(): List<LibraryBook> = emptyList()
}
