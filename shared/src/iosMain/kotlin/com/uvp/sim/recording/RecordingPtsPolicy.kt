package com.uvp.sim.recording

/**
 * iOS 录像喂 AVAssetWriter 前的 PTS 判定规则。
 *
 * 约束:
 * - 第一帧(首个可用 IDR)建立 baseline,相对时间从 0 起
 * - 相对时间 < 0 直接拒绝
 * - 相对时间必须严格大于上一条已 append 的 relPtsUs
 */
internal object RecordingPtsPolicy {
    fun classify(
        baselinePtsUs: Long,
        lastAppendedRelPtsUs: Long,
        rawPtsUs: Long,
    ): Decision {
        if (baselinePtsUs == -1L) {
            return Decision.FirstSample(
                baselinePtsUs = rawPtsUs,
                relPtsUs = 0L,
            )
        }
        val relPtsUs = rawPtsUs - baselinePtsUs
        if (relPtsUs < 0L) {
            return Decision.Negative(
                relPtsUs = relPtsUs,
                baselinePtsUs = baselinePtsUs,
            )
        }
        if (lastAppendedRelPtsUs >= 0L && relPtsUs <= lastAppendedRelPtsUs) {
            return Decision.NonMonotonic(
                relPtsUs = relPtsUs,
                lastAppendedRelPtsUs = lastAppendedRelPtsUs,
            )
        }
        return Decision.Accept(relPtsUs)
    }

    internal sealed class Decision {
        data class FirstSample(
            val baselinePtsUs: Long,
            val relPtsUs: Long,
        ) : Decision()

        data class Accept(val relPtsUs: Long) : Decision()

        data class Negative(
            val relPtsUs: Long,
            val baselinePtsUs: Long,
        ) : Decision()

        data class NonMonotonic(
            val relPtsUs: Long,
            val lastAppendedRelPtsUs: Long,
        ) : Decision()
    }
}
