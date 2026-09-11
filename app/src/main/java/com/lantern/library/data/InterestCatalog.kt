package com.lantern.library.data

data class StoryInterest(
    val id: String,
    val label: String,
    val queries: List<String>
)

object InterestCatalog {
    val all = listOf(
        StoryInterest("fantasy", "Fantasy", listOf("subject:Fantasy")),
        StoryInterest("romance", "Romance", listOf("subject:Romance")),
        StoryInterest("romantasy", "Romantasy", listOf("romantasy", "subject:Fantasy subject:Romance")),
        StoryInterest("dark_romance", "Dark Romance", listOf("\"dark romance\"")),
        StoryInterest("mystery", "Mystery", listOf("subject:Mystery")),
        StoryInterest("cozy_mystery", "Cozy Mystery", listOf("\"cozy mystery\"")),
        StoryInterest("thriller", "Thriller", listOf("subject:Thriller")),
        StoryInterest("horror", "Horror", listOf("subject:Horror")),
        StoryInterest("scifi", "Science Fiction", listOf("subject:\"Science Fiction\"")),
        StoryInterest("historical", "Historical Fiction", listOf("subject:\"Historical Fiction\"")),
        StoryInterest("hist_romance", "Historical Romance", listOf("subject:\"Historical Romance\"")),
        StoryInterest("contemporary", "Contemporary Romance", listOf("subject:\"Contemporary Romance\"")),
        StoryInterest("adventure", "Adventure", listOf("subject:Adventure")),
        StoryInterest("ya", "Young Adult", listOf("subject:\"Young Adult\"")),
        StoryInterest("na", "New Adult", listOf("\"new adult\"")),
        StoryInterest("paranormal", "Paranormal", listOf("subject:Paranormal")),
        StoryInterest("literary", "Literary Fiction", listOf("subject:\"Literary Fiction\""))
    )

    fun byId(id: String): StoryInterest? = all.firstOrNull { it.id == id }

    fun key(ids: List<String>): String =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
}
