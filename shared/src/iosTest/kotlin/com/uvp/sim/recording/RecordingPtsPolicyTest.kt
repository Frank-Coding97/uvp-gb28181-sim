package com.uvp.sim.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecordingPtsPolicyTest {

    @Test
    fun first_sample_establishes_baseline_and_returns_zero_rel_pts() {
        val decision = RecordingPtsPolicy.classify(
            baselinePtsUs = -1L,
            lastAppendedRelPtsUs = -1L,
            rawPtsUs = 5_440_540_265L,
        )

        val first = assertIs<RecordingPtsPolicy.Decision.FirstSample>(decision)
        assertEquals(5_440_540_265L, first.baselinePtsUs)
        assertEquals(0L, first.relPtsUs)
    }

    @Test
    fun later_sample_must_be_strictly_greater_than_last_appended_rel_pts() {
        val decision = RecordingPtsPolicy.classify(
            baselinePtsUs = 5_440_540_265L,
            lastAppendedRelPtsUs = 133_310L,
            rawPtsUs = 5_440_640_248L,
        )

        val nonMonotonic = assertIs<RecordingPtsPolicy.Decision.NonMonotonic>(decision)
        assertEquals(99_983L, nonMonotonic.relPtsUs)
        assertEquals(133_310L, nonMonotonic.lastAppendedRelPtsUs)
    }

    @Test
    fun strictly_increasing_sample_is_accepted() {
        val decision = RecordingPtsPolicy.classify(
            baselinePtsUs = 5_440_540_265L,
            lastAppendedRelPtsUs = 133_310L,
            rawPtsUs = 5_440_740_230L,
        )

        val accepted = assertIs<RecordingPtsPolicy.Decision.Accept>(decision)
        assertEquals(199_965L, accepted.relPtsUs)
    }
}
