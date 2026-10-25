package com.lantern.library.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

/**
 * The moment a book leaves the shelf.
 *
 * The shelf behind stays visible but softened, and the book shows up as its cover resting
 * on top of a closed hardback, with its reading progress underneath. Tapping the book
 * opens it; tapping anywhere else puts it back on the shelf.
 */
@Composable
fun BookLiftOverlay(
    book: LibraryBook,
    dark: Boolean,
    onRead: () -> Unit,
    onDismiss: () -> Unit
) {
    var entered by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) { entered = true }
    val lift by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "book-lift"
    )

    val scrim = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C150E08))
            .clickable(indication = null, interactionSource = scrim, onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 34.dp)
                .graphicsLayer {
                    alpha = lift
                    // the book rises toward the reader as it comes off the shelf
                    translationY = (1f - lift) * 60.dp.toPx()
                    scaleX = 0.88f + 0.12f * lift
                    scaleY = 0.88f + 0.12f * lift
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clickable(onClick = onRead),
                contentAlignment = Alignment.Center
            ) {
                ClosedBook(book, dark)
            }

            Spacer(Modifier.height(18.dp))

            // progress sits underneath the closed book
            Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF3A2A1C))
                ) {
                    val p = book.progress.coerceIn(0f, 1f)
                    if (p > 0f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(p)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFC8A96B), Color(0xFFE8C87A))
                                    )
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    progressLabel(book),
                    color = Color(0xFFD9C7A8),
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                book.title,
                color = NightText,
                fontFamily = Playfair,
                fontSize = 21.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                book.author,
                color = Color(0xFFC6B49A),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))

            Text(
                if (book.progress > 0.005f) "Continue reading" else "Start reading",
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFFC8A96B))
                    .clickable(onClick = onRead)
                    .padding(horizontal = 26.dp, vertical = 11.dp),
                color = Color(0xFF2A1B10),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Tap anywhere to put it back",
                color = Color(0xFF9C8A72),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * A closed hardback: leather boards, a page block along the fore-edge, and the jacket art
 * laid on top, tilted very slightly the way a book sits in the hand.
 */
@Composable
private fun ClosedBook(book: LibraryBook, dark: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val coverWidth = maxWidth * 0.72f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // the closed book underneath
            Box(
                Modifier
                    .width(coverWidth + 14.dp)
                    .aspectRatio(0.70f)
                    .offset(x = 9.dp, y = 10.dp)
                    .graphicsLayer { shadowElevation = 18.dp.toPx() }
                    .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF3B2517), Color(0xFF5A3A24), Color(0xFF3A2415))
                        )
                    )
                    .border(1.dp, Color(0x66C8A96B), RoundedCornerShape(6.dp))
            ) {
                // page block at the fore-edge
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(11.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFEDE0C6), Color(0xFFCBBB9B), Color(0xFFB3A184))
                            )
                        )
                )
                // rounded spine on the near edge
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(7.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF2A1A0F), Color(0xFF442C1B))
                            )
                        )
                )
            }

            // jacket art, lifted slightly off the boards
            CoverFace(
                title = book.title,
                localRes = book.localCover,
                pathOrUrl = book.remoteCover,
                modifier = Modifier
                    .width(coverWidth)
                    .aspectRatio(0.68f)
                    .offset(x = (-7).dp, y = (-8).dp)
                    .graphicsLayer {
                        rotationZ = -1.4f
                        shadowElevation = 26.dp.toPx()
                        ambientShadowColor = Color.Black
                        spotShadowColor = Color.Black
                    },
                dark = dark
            )
        }
    }
}

private fun progressLabel(book: LibraryBook): String {
    val pct = (book.progress * 100f).toInt().coerceIn(0, 100)
    if (book.pageCount <= 1) return "$pct% read"
    val page = (book.currentPage + 1).coerceIn(1, book.pageCount)
    return "$pct%  ·  page $page of ${book.pageCount}"
}

/** Text colour that works on the warm overlay in both themes. */
internal fun shelfInk(dark: Boolean): Color = if (dark) NightText else Ink
