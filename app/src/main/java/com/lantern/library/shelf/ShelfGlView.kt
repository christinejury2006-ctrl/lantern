package com.lantern.library.shelf

import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the bookcase.
 *
 * The scene lives in a single [TextureView] with its own OpenGL context. Taps land on a
 * transparent overlay rather than the view itself, so hit testing stays in Compose and the
 * texture view never has to be wrapped in a clickable.
 */
@Composable
fun ShelfGlView(
    prepared: ShelfPrepared,
    slots: List<ShelfLayout.Slot>,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onLifted: (slotIndex: Int) -> Unit = {},
    onOpen: (slotIndex: Int) -> Unit = {},
    onError: (String) -> Unit = {},
    onStats: (fps: Int, p95Ms: Float, drawCalls: Int, moving: Boolean) -> Unit = { _, _, _, _ -> }
) {
    val firstFrame = rememberUpdatedState(onFirstFrame)
    val lifted = rememberUpdatedState(onLifted)
    val open = rememberUpdatedState(onOpen)
    val error = rememberUpdatedState(onError)
    val stats = rememberUpdatedState(onStats)

    // Rebuilt only if the books change: the scene owns GPU memory.
    val scene = remember(prepared, slots) {
        ShelfGlScene(
            prepared = prepared,
            slots = slots,
            onFirstFrame = { firstFrame.value() },
            onStats = { fps, p95, calls, moving -> stats.value(fps, p95, calls, moving) },
            onError = { message -> error.value(message) }
        )
    }

    DisposableEffect(scene) {
        onDispose { scene.release() }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                TextureView(it).apply {
                    // Opaque lets the compositor skip blending the whole surface.
                    isOpaque = true
                    surfaceTextureListener = scene
                }
            }
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(scene) {
                    detectTapGestures { offset ->
                        val tap = scene.tap(offset.x, offset.y)
                        when {
                            tap.open -> open.value(tap.slot)
                            else -> lifted.value(tap.slot)
                        }
                    }
                }
        )
    }
}
