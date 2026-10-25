package com.lantern.library.shelf

import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the native bookcase.
 *
 * The scene lives in a single [TextureView] handed to Filament. Taps land on a transparent
 * overlay rather than the view itself, so hit testing stays in Compose and the texture view
 * never has to be wrapped in a clickable.
 */
@Composable
fun FilamentShelf(
    prepared: ShelfPrepared,
    slots: List<ShelfLayout.Slot>,
    modifier: Modifier = Modifier,
    onStats: (fps: Int, p95Ms: Float, drawCalls: Int, moving: Boolean) -> Unit = { _, _, _, _ -> },
    onOpen: (slotIndex: Int) -> Unit = {},
    onLifted: (slotIndex: Int) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val stats = rememberUpdatedState(onStats)
    val open = rememberUpdatedState(onOpen)
    val lifted = rememberUpdatedState(onLifted)
    val error = rememberUpdatedState(onError)

    // Rebuilt only if the books change: the scene owns GPU memory, so it is not cheap.
    val renderer = remember(prepared, slots) {
        ShelfRenderer(
            prepared = prepared,
            slots = slots,
            onStats = { fps, p95, calls, moving -> stats.value(fps, p95, calls, moving) },
            onOpen = { index -> open.value(index) },
            onTap = { index -> lifted.value(index) },
            onError = { message -> error.value(message) }
        )
    }

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    // Opaque lets the compositor skip blending the whole surface.
                    isOpaque = true
                    surfaceTextureListener = renderer
                }
            }
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(renderer) {
                    detectTapGestures { offset -> renderer.tap(offset.x, offset.y) }
                }
        )
    }
}
