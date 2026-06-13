package com.uvp.sim.recording

/**
 * mp4 video sample → 不带起始码的 NAL list 拆分。
 *
 * 自适应识别两种格式:
 *   - **AVCC**(`[len4][nalu]...`):MediaExtractor 标准输出,前 4 字节大端整数 = 后续 NAL 长度
 *   - **AnnexB**(`00 00 00 01 [nalu] 00 00 00 01 [nalu]...`):某些 mp4 (CameraX
 *     Recorder 实测)直接用 Annex B 起始码隔开
 *
 * 这个判别函数提取到 commonMain 是为了 commonTest 能直接覆盖 — 否则它埋在
 * androidMain 里只能靠真机/instrumentation test,会漏 bug(2026-06-13 真有过一次:
 * 把 AnnexB 当 AVCC 解,前 4 字节 0x00000001 被读成 length=1,丢了 IDR 帧 140KB)。
 */
object Mp4SampleParser {

    /** Sniff 前 4 字节判断格式,失配走 AVCC 兜底。 */
    fun toNalList(sample: ByteArray): List<ByteArray> {
        if (sample.size < 4) return emptyList()
        if (isAnnexBStartCode4(sample, 0) || isAnnexBStartCode3(sample, 0)) {
            return splitAnnexB(sample)
        }
        return splitAvcc(sample)
    }

    /** AVCC: `[len4][nalu]...`,length 是大端无符号 32 位整数。 */
    fun splitAvcc(sample: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= sample.size) {
            val len = ((sample[i].toInt() and 0xFF) shl 24) or
                ((sample[i + 1].toInt() and 0xFF) shl 16) or
                ((sample[i + 2].toInt() and 0xFF) shl 8) or
                (sample[i + 3].toInt() and 0xFF)
            i += 4
            if (len <= 0 || i + len > sample.size) break
            val nal = ByteArray(len)
            sample.copyInto(nal, 0, i, i + len)
            out += nal
            i += len
        }
        return out
    }

    /** AnnexB: 起始码 `00 00 00 01` 或 `00 00 01` 隔开 NAL。 */
    fun splitAnnexB(sample: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val starts = mutableListOf<Int>()  // 每个 NAL 的起点(起始码后第一个字节)
        var i = 0
        while (i < sample.size) {
            if (isAnnexBStartCode4(sample, i)) {
                starts += i + 4
                i += 4
            } else if (isAnnexBStartCode3(sample, i)) {
                starts += i + 3
                i += 3
            } else {
                i += 1
            }
        }
        for (idx in starts.indices) {
            val s = starts[idx]
            val e = if (idx + 1 < starts.size) {
                // 下一个起始码的位置 — 起点回退 3/4 字节
                val nextStart = starts[idx + 1]
                if (nextStart >= 4 && isAnnexBStartCode4(sample, nextStart - 4)) nextStart - 4
                else nextStart - 3
            } else sample.size
            if (e > s) out += sample.copyOfRange(s, e)
        }
        return out
    }

    /** 去掉 csd / NAL 数组里可能的 Annex-B 起始码,返回纯 NAL。 */
    fun stripStartCode(data: ByteArray): ByteArray {
        if (isAnnexBStartCode4(data, 0)) return data.copyOfRange(4, data.size)
        if (isAnnexBStartCode3(data, 0)) return data.copyOfRange(3, data.size)
        return data
    }

    private fun isAnnexBStartCode4(b: ByteArray, off: Int): Boolean =
        off + 4 <= b.size &&
            b[off] == 0.toByte() && b[off + 1] == 0.toByte() &&
            b[off + 2] == 0.toByte() && b[off + 3] == 1.toByte()

    private fun isAnnexBStartCode3(b: ByteArray, off: Int): Boolean =
        off + 3 <= b.size &&
            b[off] == 0.toByte() && b[off + 1] == 0.toByte() && b[off + 2] == 1.toByte()
}
