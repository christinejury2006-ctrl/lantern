package com.lantern.library

/**
 * Bridges hardware volume-key presses from the Activity to the active reader.
 * A null handler means volume keys retain their normal Android behaviour.
 */
object ReaderVolumeKeys {
    @Volatile
    private var handler: ((Int) -> Boolean)? = null

    fun register(next: (Int) -> Boolean): () -> Unit {
        handler = next
        return {
            if (handler === next) handler = null
        }
    }

    fun navigate(delta: Int): Boolean = handler?.invoke(delta) ?: false
}
