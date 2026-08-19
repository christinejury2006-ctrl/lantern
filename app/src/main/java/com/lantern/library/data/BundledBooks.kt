package com.lantern.library.data

import com.lantern.library.R

object BundledBooks {
    fun seed(): List<LibraryBook> = listOf(
        LibraryBook(
            id = "eldoria",
            title = "The Chronicles of Eldoria",
            author = "Olivia Sterling",
            localCover = R.drawable.cover_eldoria,
            format = BookFormat.TEXT,
            origin = BookOrigin.BUNDLED,
            pageCount = 520,
            currentPage = 0,
            lastReadAt = System.currentTimeMillis(),
            category = "Fantasy",
            synopsis = "When the last lantern of Eldoria gutters, a cartographer must walk the hidden roads before the map forgets their names.",
            chapters = eldoria()
        ),
        LibraryBook(
            id = "aurora",
            title = "Aurora's Gift",
            author = "Freya Lind",
            localCover = R.drawable.cover_aurora,
            format = BookFormat.TEXT,
            origin = BookOrigin.BUNDLED,
            pageCount = 256,
            category = "Fantasy",
            synopsis = "A meteorologist at the edge of the world is given one night of impossible colour.",
            chapters = listOf(
                Chapter(
                    "Green Hour",
                    "The instruments lied first. Temperature, wind, magnetic north — all of them politely disagreed with the sky. Then the colour arrived, not as light but as permission. I stepped outside without a coat. The snow did not feel cold. Somewhere above the lake a door opened that had never been a door, and I understood why the old maps left this place blank.\n\nI wrote the hour down anyway. Paper is stubborn. It wants a time even when the sky has stopped offering one."
                )
            )
        ),
        LibraryBook(
            id = "ember",
            title = "The Last Ember",
            author = "Rowan Hale",
            localCover = R.drawable.cover_ember,
            format = BookFormat.TEXT,
            origin = BookOrigin.BUNDLED,
            pageCount = 412,
            category = "Fantasy",
            synopsis = "In a city that has outlawed fire, a glassblower hides the last living coal.",
            chapters = listOf(
                Chapter(
                    "Ash Law",
                    "They measured warmth the way other cities measured crime. A kettle left too long, a fever, a blush — all of it was evidence. I kept the ember in a hollow bead beneath my tongue, and when I spoke in the market my words came out slightly scorched. No one noticed. People rarely notice the small rebellions that keep them alive."
                )
            )
        )
    )

    private fun eldoria() = listOf(
        Chapter(
            "The Hidden Road",
            """
            Late light lay over Eldoria like a held breath. I had been told the hidden road would show itself only to someone who had already been lost. I was not lost. I had a compass, three reliable maps, and a letter from the Queen that weighed more than its paper.

            The lantern at the western gate guttered. No wind. The flame simply reconsidered its loyalty and went out. In the sudden blue I saw the first mark — a seam in the air, thin as a hair, running from the cobbles into the orchards. I did what any honest mapmaker would do. I followed it.

            Beyond the last pear tree the land forgot its manners. Distances folded. A river I had charted last spring ran uphill with a look of apology. Birds crossed the seam and came out younger. I wrote none of this down. Ink, I suspected, would not survive the truth.

            A woman sat on a stone that had not been there a moment before. She wore a coat the colour of wet slate and was eating an apple that steamed as if it had just been pulled from a fire.

            “You got the letter,” she said, not looking up. “All Eldoria waited as if a title can be hid. Hidden from what, I wonder. From itself, most days.”

            I showed her the Queen’s seal. She laughed, a dry, kind sound, and flicked a pip into the grass.

            “Walk if you can keep your feet. The map in your satchel is already lying. When it starts telling the truth, you will wish it would stop.”
            """.trimIndent()
        ),
        Chapter(
            "Names the River Kept",
            """
            We walked until the orchards became a marsh and the marsh became a floor of glass. Beneath it, old processions still moved — banners, horses, a child waving at a sky that no longer existed. I asked the woman her name.

            “I lent it to the river,” she said. “It pays better interest than kings.”

            At dusk we reached a village that existed only on Tuesdays. The baker sold bread that remembered being wheat. I bought a loaf with a coin from last year; the baker weighed it on a scale that preferred stories to metal.

            “You are the cartographer,” he said. “We have a hole in the square. If you could draw around it, perhaps it would be ashamed and close.”

            I tried. The hole accepted the pencil, then the page, then the idea of a border. I stopped before it took my hand.
            """.trimIndent()
        ),
        Chapter(
            "The Court of Unlit Rooms",
            """
            The palace of Eldoria had always been famous for its windows. Now the glass held only its own reflection. Courtiers moved through the halls with lanterns they were not allowed to light.

            I was received in a chamber painted with constellations that no longer matched the sky. The Queen did not sit.

            “You drew the western orchards as continuous,” she said. “They are not. You drew the river as loyal. It is not.” She smiled, and the smile was a tired country. “A kingdom is only a consensus. Remove enough words and the roads forget where they were going.”

            She tapped my newest sketch. “Walk the hidden road to the source. Bring back whatever is eating the words. Do not become a story I have to explain to children.”
            """.trimIndent()
        ),
        Chapter(
            "What the Map Admitted",
            """
            On the fourth day the map in my satchel began to correct itself. Hills I had invented for a pretty contour line stood up, offended, and became real. A lake I had omitted out of haste appeared at my feet and soaked my boots. I apologised. The lake accepted this with a small wave.

            Ahead, the hidden road narrowed into a doorway that had no house. Through it I heard the sound of a quill, patient and vast, crossing out the world one noun at a time.
            """.trimIndent()
        )
    )
}
