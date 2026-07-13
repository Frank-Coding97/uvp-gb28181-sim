package com.uvp.sim.recording

/** Small pure helper for SEEK_TO_PREVIOUS_SYNC semantics. */
class KeyframeIndex {
    private val points = mutableListOf<Long>()
    private var finalized = false

    fun add(ptsUs: Long) {
        check(!finalized) { "KeyframeIndex already finalized" }
        points += ptsUs
    }

    fun finalizeIndex() {
        if (!finalized) {
            points.sort()
            finalized = true
        }
    }

    fun findPreviousSync(targetUs: Long, fallbackUs: Long = 0L): Long {
        check(finalized) { "KeyframeIndex must be finalized before lookup" }
        if (points.isEmpty()) return fallbackUs
        var lo = 0
        var hi = points.lastIndex
        var result = points.first()
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val value = points[mid]
            if (value <= targetUs) {
                result = value
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
