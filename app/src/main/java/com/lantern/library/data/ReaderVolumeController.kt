package com.lantern.library.data

import android.view.KeyEvent

/** Routes hardware volume keys to the active reader only; outside the reader Android owns volume. */
object ReaderVolumeController {
    @Volatile private var handler: ((forward: Boolean) -> Unit)? = null

    fun bind(onTurnPage: (forward: Boolean) -> Unit) { handler = onTurnPage }
    fun clear() { handler = null }

    fun consume(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val active = handler ?: return false
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            active(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        }
        return true
    }
}
