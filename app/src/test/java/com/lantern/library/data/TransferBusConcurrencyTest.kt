package com.lantern.library.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transfer progress written from several upload threads at once.
 *
 * This is the regression test for the release crash of build 276 (commit `0342cdf`), whose retraced
 * trace ended in
 *
 * ```
 * androidx.compose.runtime.snapshots.SnapshotApplyConflictException
 *     at SnapshotApplyResult$Failure.check()
 *     at com.lantern.library.data.TransferBus.failed(...)
 *     at com.lantern.library.data.LanternStore$uploadListener$1.onFailed(...)
 *     at com.lantern.library.data.UploadPool$run$3$2$job$1.invokeSuspend(...)
 * ```
 *
 * The mechanism, in one sentence: [TransferBus] published every change with its own
 * `Snapshot.withMutableSnapshot`, and [UploadPool] runs one coroutine per book on up to three slots,
 * so two uploads could open and apply overlapping snapshots at the same instant. Compose detects
 * that as a conflicting apply and throws — which is correct of Compose, and fatal here, because the
 * throw happened inside a per-book upload job with no handler above it.
 *
 * The test drives the production [TransferBus] the way the pool drives it: many threads, interleaved
 * calls, no coordination. It asserts two things that must both be true for the app to survive an
 * upload at launch:
 *
 * 1. **No call throws.** A `SnapshotApplyConflictException` here is exactly the app dying.
 * 2. **The map still answers.** After the storm every book's last state is readable and consistent,
 *    so the shelf draws a percentage rather than nothing.
 */
class TransferBusConcurrencyTest {

    @After
    fun tearDown() {
        TransferBus.clearAll()
    }

    @Test
    fun `progress from several upload threads at once never throws`() {
        TransferBus.clearAll()
        val threads = 8
        val iterations = 400
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread({
                // Every thread starts writing at the same moment: overlapping mutable snapshots are
                // the condition under test, not an accident of scheduling.
                start.await()
                val book = "book-$t"
                try {
                    repeat(iterations) { i ->
                        TransferBus.start(book, 1_000_000L)
                        TransferBus.sent(book, i * 1_000L, 1_000_000L)
                        if (i % 4 == 0) TransferBus.verifying(book, 1_000_000L)
                        if (i % 7 == 0) TransferBus.paused(book, i * 1_000L, 1_000_000L, "interrupted")
                        if (i % 11 == 0) TransferBus.failed(book, i * 1_000L, 1_000_000L, "no network")
                        // A different book from the same thread, so the map is written, not just one
                        // entry of it.
                        TransferBus.restorePreparing("restore-$t", 2_000L)
                        if (i % 5 == 0) TransferBus.clear("restore-$t")
                    }
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    finished.countDown()
                }
            }, "upload-$t").start()
        }

        start.countDown()
        assertTrue("the writers must finish", finished.await(120, TimeUnit.SECONDS))

        val first = failures.firstOrNull()
        assertTrue(
            "writing progress from ${threads} upload threads must never throw: ${first?.javaClass?.name} ${first?.message}",
            failures.isEmpty()
        )

        // The map is still usable and holds every book that ended with a state.
        assertTrue("the map must still answer", TransferBus.snapshot().isNotEmpty())
        repeat(threads) { t ->
            TransferBus.progressFor("book-$t")
        }
    }

    @Test
    fun `a mirrored pair of writers ends in a readable state, not a torn one`() {
        // Two books, two threads, each writing only its own entry, so the only shared object is the
        // map itself. Under the old lock-free apply this is what threw; a reader must still get one
        // coherent value per book.
        TransferBus.clearAll()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failures = CopyOnWriteArrayList<Throwable>()
        listOf("a", "b").forEach { id ->
            Thread({
                start.await()
                try {
                    // `start` writes unconditionally (no percentage throttling), so this is 500
                    // applies per thread against the same map.
                    repeat(500) { i -> TransferBus.start(id, i.toLong()) }
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    finished.countDown()
                }
            }).start()
        }
        start.countDown()
        assertTrue(finished.await(60, TimeUnit.SECONDS))
        assertTrue("no apply conflict: ${failures.firstOrNull()}", failures.isEmpty())
        assertEquals(499L, TransferBus.progressFor("a")?.totalBytes)
        assertEquals(499L, TransferBus.progressFor("b")?.totalBytes)
    }
}
