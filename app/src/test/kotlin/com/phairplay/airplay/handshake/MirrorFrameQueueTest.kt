package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MirrorFrameQueueTest — tests the memory budget of the mirror frame queue.
 *
 * WHY: the security review (docs/reviews/2026-10-24-pr19-pr17-issue18-review.md, §3.3, finding 2)
 * named the missing byte budget of the mirror frame queue as an open security finding: the queue
 * was bounded by item count only (90 items × up to 8 MB per payload ≈ 720 MB), so a hostile device
 * on the LAN could push the receiver into an OutOfMemoryError.
 *
 * WHAT WE TEST:
 * - the queue never holds more bytes than its budget, even when the budget is exceeded on purpose
 * - the OLDEST items are the ones that are dropped (the newest frames are the useful ones)
 * - the producer (network thread) never blocks when the queue is full
 * - an item that is bigger than the whole budget is refused instead of breaking the budget
 * - the byte counter is exact — it always equals the real occupancy of the queue
 * - the item limit still works independently of the byte budget
 *
 * HOW: [MirrorFrameQueue] is a pure Kotlin class without Android dependencies, so it can be
 * exercised directly in a JVM unit test.
 */
class MirrorFrameQueueTest {

    // ─── The byte budget ─────────────────────────────────────────────────────

    @Test
    fun `offer keeps the queued bytes within the budget when it is exceeded on purpose`() {
        // 1 MB budget, ten frames of 256 KB — the budget fits four of them.
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        repeat(10) { queue.offer(frame(256 * 1024, it)) }

        assertTrue(
            "the queue must never hold more than its budget, was ${queue.byteSize} B",
            queue.byteSize <= 1024L * 1024
        )
        assertEquals("four 256 KB frames fit into a 1 MB budget", 4, queue.size)
        assertEquals(6L, queue.droppedItems)
    }

    @Test
    fun `offer drops the oldest frames first`() {
        // 250 KB budget, three frames of 100 KB — only two fit.
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 250L * 1024)
        queue.offer(frame(100 * 1024, 1))
        queue.offer(frame(100 * 1024, 2))
        val result = queue.offer(frame(100 * 1024, 3))

        assertEquals("one frame had to make room for the new one", 1, result.dropped)
        assertTrue("the new frame must be accepted", result.accepted)
        assertEquals(2, queue.size)

        // The head of the queue is the newest-but-one: frame 1 (the oldest) is gone.
        val first = queue.poll(0) as MirrorFrameQueue.Frame
        val second = queue.poll(0) as MirrorFrameQueue.Frame
        assertEquals("the oldest frame must have been dropped", 2, markerOf(first))
        assertEquals("the newest frame must survive", 3, markerOf(second))
        assertNull("only two frames were left", queue.poll(0))
    }

    @Test
    fun `the byte counter always equals the real occupancy of the queue`() {
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        repeat(25) { queue.offer(frame(64 * 1024, it)) }
        queue.poll(0)
        queue.poll(0)

        val realOccupancy = queue.snapshot().sumOf { (it as MirrorFrameQueue.Frame).annexB.size.toLong() }
        assertEquals("the reported byte count must match the queued items", realOccupancy, queue.byteSize)
    }

    @Test
    fun `an item that is bigger than the whole budget is refused`() {
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        val result = queue.offer(frame(2 * 1024 * 1024, 1))

        assertTrue("an oversized item must not be accepted", !result.accepted)
        assertEquals("nothing may be queued", 0, queue.size)
        assertEquals("the budget must stay untouched", 0L, queue.byteSize)
    }

    @Test
    fun `the item limit is enforced independently of the byte budget`() {
        // A huge byte budget, so only the item limit can bite.
        val queue = MirrorFrameQueue(maxItems = 2, maxBytes = 64L * 1024 * 1024)
        queue.offer(frame(1024, 1))
        queue.offer(frame(1024, 2))
        val result = queue.offer(frame(1024, 3))

        assertEquals("the oldest item had to go", 1, result.dropped)
        assertEquals(2, queue.size)
    }

    @Test
    fun `the default budget is 32 MB`() {
        // The default is part of the contract with the service: it is what keeps the queue far
        // below the 150 MB process budget of NFR-01.
        assertEquals(32L * 1024 * 1024, MirrorFrameQueue.DEFAULT_MAX_BYTES)
        assertEquals(90, MirrorFrameQueue.DEFAULT_MAX_ITEMS)
    }

    // ─── The producer must never block ────────────────────────────────────────

    @Test
    fun `offer never blocks while the budget is permanently exceeded`() {
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        val started = System.nanoTime()
        repeat(2000) { queue.offer(frame(512 * 1024, it)) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("offer must not block, took ${elapsedMs} ms", elapsedMs < 2000)
        assertTrue("the budget must hold", queue.byteSize <= 1024L * 1024)
    }

    @Test
    fun `the byte budget holds while a producer and a consumer run at the same time`() {
        // The real situation: the network thread produces, the decoder thread consumes. The
        // accounting must stay exact under concurrency — with a plain volatile counter the
        // read-modify-write updates lose each other and the cap stops being enforced.
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        val producerDone = CountDownLatch(1)
        val producer = Thread {
            repeat(500) { queue.offer(frame(64 * 1024, it)) }
            producerDone.countDown()
        }
        producer.start()

        var consumed = 0
        while (producerDone.count > 0) {
            if (queue.poll(1) != null) consumed++
            assertTrue(
                "the budget must hold at any time, was ${queue.byteSize} B",
                queue.byteSize <= 1024L * 1024
            )
        }
        producer.join(5000)
        while (queue.poll(0) != null) consumed++

        assertTrue("the consumer must have taken frames, got $consumed", consumed > 0)
        val realOccupancy = queue.snapshot().sumOf { (it as MirrorFrameQueue.Frame).annexB.size.toLong() }
        assertEquals("the byte counter must stay exact", realOccupancy, queue.byteSize)
    }

    // ─── The consumer side ────────────────────────────────────────────────────

    @Test
    fun `poll returns the items in the order they were offered`() {
        val queue = MirrorFrameQueue()
        queue.offer(frame(64, 1))
        queue.offer(frame(64, 2))

        assertEquals(1, markerOf(queue.poll(10) as MirrorFrameQueue.Frame))
        assertEquals(2, markerOf(queue.poll(10) as MirrorFrameQueue.Frame))
    }

    @Test
    fun `poll returns null when no item arrives within the timeout`() {
        val queue = MirrorFrameQueue()
        val started = System.nanoTime()
        val item = queue.poll(50)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull("an empty queue must not return an item", item)
        assertTrue("poll must wait for the timeout, waited ${elapsedMs} ms", elapsedMs >= 40)
    }

    @Test
    fun `poll waits for an item that arrives shortly after the call`() {
        val queue = MirrorFrameQueue()
        val producer = Thread {
            Thread.sleep(20)
            queue.offer(frame(64, 7))
        }
        producer.start()

        val item = queue.poll(2000)
        producer.join(2000)

        assertNotNull("poll must return the late item", item)
        assertEquals(7, markerOf(item as MirrorFrameQueue.Frame))
    }

    @Test
    fun `polling an item releases its bytes from the budget`() {
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        queue.offer(frame(256 * 1024, 1))
        assertEquals(256L * 1024, queue.byteSize)

        queue.poll(10)

        assertEquals("the freed bytes must return to the budget", 0L, queue.byteSize)
    }

    @Test
    fun `clear empties the queue and the budget`() {
        val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 1024L * 1024)
        queue.offer(frame(256 * 1024, 1))
        queue.clear()

        assertEquals(0, queue.size)
        assertEquals(0L, queue.byteSize)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** A frame of [size] bytes whose first byte carries [marker] so it can be identified. */
    private fun frame(size: Int, marker: Int): MirrorFrameQueue.Frame =
        MirrorFrameQueue.Frame(ByteArray(size) { marker.toByte() })

    private fun markerOf(item: MirrorFrameQueue.Frame): Int = item.annexB[0].toInt()
}
