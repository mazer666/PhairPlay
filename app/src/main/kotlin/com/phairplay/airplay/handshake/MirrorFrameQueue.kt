package com.phairplay.airplay.handshake

/**
 * MirrorFrameQueue — bounded FIFO for the AirPlay mirroring video frames.
 *
 * WHY A SEPARATE CLASS: the queue is the only place where a hostile LAN peer can influence how
 * much memory the receiver holds. Keeping it out of [MirrorStreamServer] makes the policy
 * testable in a plain JVM unit test (no socket, no decoder, no Android runtime) and keeps the
 * server file small.
 *
 * TWO INDEPENDENT BOUNDS:
 *  - the item count ([maxItems]) — the latency bound: 90 frames are ~1.5 s at 60 fps, so the
 *    decoder is never more than that behind; and
 *  - the total number of bytes ([maxBytes]) — the memory bound. The item count alone is NOT a
 *    memory bound: a sender may push payloads of up to 8 MB each, so 90 items could keep
 *    ~720 MB alive and OOM the app process (security review 2026-10-24, §3.3, finding 2).
 *
 * WHY THE OLDEST ITEM IS DROPPED: a video stream is only decodable from an IDR frame onwards.
 * When the decoder falls behind, the *newest* frames are the ones worth keeping — the oldest
 * ones are already late — so the queue evicts from the head and the decoder resynchronises at
 * the next keyframe.
 *
 * WHY THE BYTE COUNTER IS EXACT: the reader thread (producer) and the decoder thread
 * (consumer) both change the occupancy. With a plain `volatile int` the two read-modify-write
 * updates can lose each other, and a lost *increment* makes the counter report LESS than the
 * real occupancy — which silently disables the byte cap. All accounting therefore happens under
 * one lock, and [byteSize] is always the exact sum of the queued items.
 *
 * Example:
 *   val queue = MirrorFrameQueue(maxItems = 90, maxBytes = 32L * 1024 * 1024)
 *   queue.offer(MirrorFrameQueue.Frame(annexB))   // reader thread — never blocks
 *   val item = queue.poll(20)                      // decoder thread — waits up to 20 ms
 */
class MirrorFrameQueue(
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /** A queued item. [sizeBytes] is what the item costs in the memory budget. */
    sealed class Item {
        /** The number of bytes this item occupies in the queue's memory budget. */
        abstract val sizeBytes: Int
    }

    /** A decoder configuration (SPS + PPS). Small, but it must be accounted for as well. */
    class Config(val sps: ByteArray, val pps: ByteArray) : Item() {
        override val sizeBytes: Int get() = sps.size + pps.size
    }

    /** One decoded video frame in Annex-B format. */
    class Frame(val annexB: ByteArray) : Item() {
        override val sizeBytes: Int get() = annexB.size
    }

    /**
     * The result of an [offer] call.
     *
     * @param accepted false when the item was rejected because it alone exceeds the whole
     *   memory budget — such an item can never be admitted without breaking the budget.
     * @param dropped the number of items that had to be discarded to make room for the item.
     */
    data class OfferResult(val accepted: Boolean, val dropped: Int)

    private val lock = Object()
    private val items = ArrayDeque<Item>()
    private var queuedBytes = 0L
    private var droppedTotal = 0L

    /** The number of items currently queued. */
    val size: Int get() = synchronized(lock) { items.size }

    /** The exact number of bytes currently held by the queue — never above [maxBytes]. */
    val byteSize: Long get() = synchronized(lock) { queuedBytes }

    /** The total number of items dropped since the queue was created (statistics only). */
    val droppedItems: Long get() = synchronized(lock) { droppedTotal }

    /**
     * Adds [item] to the queue, dropping the oldest items until the item fits.
     *
     * NEVER BLOCKS: the network reader thread must keep draining the socket, so an item that
     * does not fit is never waited for — the oldest items are dropped instead.
     *
     * @return whether the item was accepted and how many items were dropped for it.
     */
    fun offer(item: Item): OfferResult {
        val itemBytes = item.sizeBytes.toLong()
        var dropped = 0
        synchronized(lock) {
            if (itemBytes > maxBytes) {
                // The item alone is bigger than the whole budget. Admitting it would break the
                // budget, so it is discarded — the sender simply has to send smaller frames.
                droppedTotal++
                return OfferResult(accepted = false, dropped = 1)
            }
            while (items.isNotEmpty() &&
                (queuedBytes + itemBytes > maxBytes || items.size >= maxItems)) {
                val oldest = items.removeFirst()
                queuedBytes -= oldest.sizeBytes
                dropped++
                droppedTotal++
            }
            items.addLast(item)
            queuedBytes += itemBytes
            lock.notifyAll()
        }
        return OfferResult(accepted = true, dropped = dropped)
    }

    /**
     * Removes the oldest item and returns it, or `null` if none arrives within [timeoutMs].
     *
     * @param timeoutMs how long to wait for an item. The decoder thread uses a short timeout so
     *   it can notice that the session ended.
     */
    fun poll(timeoutMs: Long): Item? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (items.isEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                lock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            }
            val item = items.removeFirst()
            queuedBytes -= item.sizeBytes
            return item
        }
    }

    /** A snapshot of the currently queued items — used for diagnostics and tests. */
    fun snapshot(): List<Item> = synchronized(lock) { items.toList() }

    /** Drops everything in the queue (session ended). */
    fun clear() {
        synchronized(lock) {
            items.clear()
            queuedBytes = 0L
            lock.notifyAll()
        }
    }

    companion object {
        /**
         * Default item limit — ~1.5 s of video at 60 fps.
         *
         * This is the latency bound, not the memory bound; see [maxBytes] for that.
         */
        const val DEFAULT_MAX_ITEMS = 90

        /**
         * Default memory budget of the frame queue: 32 MB.
         *
         * WHY 32 MB: a real 1080p–1440p frame is typically 50–500 KB, so 32 MB holds more
         * frames than [DEFAULT_MAX_ITEMS] — a healthy stream is therefore never affected by
         * the byte budget, only pathological payloads are. At the same time 32 MB is far below
         * the process budget of NFR-01 (150 MB RAM): even in the worst case the queue cannot
         * eat a third of the app's memory. The previous count-only bound allowed ~720 MB.
         */
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
    }
}
