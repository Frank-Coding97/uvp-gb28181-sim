package com.uvp.sim.media

/**
 * G.711 A-law / μ-law software encoders.
 *
 * Both algorithms map a 16-bit linear PCM sample to one byte of compressed
 * output. Decoder tables are baked into every IP camera platform on the planet
 * so this is the safe "always works" audio codec for GB28181 simulation.
 *
 * - **A-law (G.711A)**: ITU-T G.711 A-law / Annex A — used in Europe / China.
 * - **μ-law (G.711U)**: ITU-T G.711 μ-law — used in North America / Japan.
 *
 * Both encoders take signed little-endian 16-bit PCM input.
 */
object G711 {

    fun encodeAlaw(pcm16: ShortArray): ByteArray {
        val out = ByteArray(pcm16.size)
        for (i in pcm16.indices) out[i] = linearToAlaw(pcm16[i].toInt())
        return out
    }

    fun encodeUlaw(pcm16: ShortArray): ByteArray {
        val out = ByteArray(pcm16.size)
        for (i in pcm16.indices) out[i] = linearToUlaw(pcm16[i].toInt())
        return out
    }

    /**
     * A-law encode one 16-bit signed PCM sample.
     *
     * Reference: ITU-T G.711 § 2.2. The mapping is sign + segment + quant
     * with the result XORed with 0x55 to flip alternate bits as the standard
     * specifies.
     */
    private fun linearToAlaw(pcmIn: Int): Byte {
        var pcm = pcmIn
        val sign = if (pcm < 0) {
            pcm = -pcm
            0x00
        } else 0x80
        if (pcm > 32635) pcm = 32635

        val seg = when {
            pcm < 256 -> 0
            pcm < 512 -> 1
            pcm < 1024 -> 2
            pcm < 2048 -> 3
            pcm < 4096 -> 4
            pcm < 8192 -> 5
            pcm < 16384 -> 6
            else -> 7
        }
        val mantissa = if (seg < 1) (pcm ushr 4) and 0x0F
        else (pcm ushr (seg + 3)) and 0x0F
        val alaw = (sign or (seg shl 4) or mantissa) xor 0x55
        return alaw.toByte()
    }

    /**
     * μ-law encode one 16-bit signed PCM sample.
     *
     * Reference: ITU-T G.711 § 2.1.
     */
    private fun linearToUlaw(pcmIn: Int): Byte {
        var pcm = pcmIn
        val sign = if (pcm < 0) {
            pcm = -pcm
            0x80
        } else 0x00
        pcm += BIAS
        if (pcm > CLIP) pcm = CLIP

        var seg = 7
        var mask = 0x4000
        while ((pcm and mask) == 0 && seg > 0) {
            seg--
            mask = mask ushr 1
        }
        val mantissa = (pcm ushr (seg + 3)) and 0x0F
        val ulaw = (sign or (seg shl 4) or mantissa).inv() and 0xFF
        return ulaw.toByte()
    }

    private const val BIAS = 0x84
    private const val CLIP = 32635
}
