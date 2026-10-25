package com.lantern.library.shelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import com.lantern.library.R

/** Everything the GPU needs, built off the main thread before the surface appears. */
class ShelfPrepared(
    val atlas: Bitmap,
    val wood: Bitmap?,
    val room: Bitmap?
)

/**
 * Builds the slow parts of the shelf — the painted atlas and the two room photographs — away
 * from the main thread, so the screen never stalls opening. There is no shader to compile:
 * the scene's shader is a short string handed straight to the driver.
 */
object ShelfPreparer {

    fun prepare(
        context: Context,
        books: List<BookArt>,
        onPhase: (String) -> Unit = {}
    ): ShelfPrepared {
        onPhase("Painting ${books.size} spines")
        val atlas = ShelfAtlas.paint(books)
        onPhase("Hanging the room")
        val wood = decode(context, R.drawable.wood_shelf)
        val room = decode(context, R.drawable.library_room)
        return ShelfPrepared(atlas = atlas, wood = wood, room = room)
    }

    private fun decode(context: Context, res: Int): Bitmap? =
        runCatching { BitmapFactory.decodeResource(context.resources, res) }.getOrNull()
}
