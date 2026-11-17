package com.abhinavxt.novelforge.data.sync

/**
 * Hybrid logical clock. hlc = physicalMs shl 16 or counter.
 *
 * Counter overflow: there is no separate counter field; a value is just a Long and the next
 * value is `last + 1`. When 65535 values are issued within one millisecond the counter carries
 * into the physical part, so the clock runs one artificial millisecond ahead. It never goes
 * backwards and never repeats. Values saturate at Long.MAX_VALUE.
 */
class HybridClock(private val now: () -> Long, private val offset: () -> Long) {
    private var last = 0L
    private val lock = Any()

    /**
     * A value strictly greater than anything ticked or observed before, based on
     * max(now() + offset(), physical ?: 0, last).
     */
    fun tick(physical: Long? = null): Long = synchronized(lock) {
        val base = clampPhysical(maxOf(now() + offset(), physical ?: 0L)) shl SHIFT
        // Saturate instead of overflowing: a hostile value near Long.MAX must never make the clock negative.
        last = if (last >= Long.MAX_VALUE - 1) Long.MAX_VALUE else maxOf(base, last + 1)
        last
    }

    /** Move past a remote value, so everything ticked later is greater. */
    fun observe(remote: Long) {
        synchronized(lock) { if (remote > last) last = remote }
    }

    /**
     * The hlc for a known real edit time (device clock ms), corrected by the server offset.
     * Does not advance the clock: an edit made in the past must not outrank newer remote writes.
     */
    fun at(deviceMs: Long): Long = clampPhysical(deviceMs + offset()) shl SHIFT

    companion object {
        const val SHIFT = 16
        private const val MAX_PHYSICAL = Long.MAX_VALUE ushr SHIFT

        /** Keeps `ms shl SHIFT` a positive Long. */
        private fun clampPhysical(ms: Long): Long = ms.coerceIn(0L, MAX_PHYSICAL)

        fun physicalOf(hlc: Long): Long = hlc ushr SHIFT
    }
}
