package com.uvp.sim.sip

/**
 * Pure-Kotlin MD5 implementation — RFC 1321.
 *
 * Used by [DigestAuth] for HTTP Digest authentication (RFC 2617). We don't
 * use the platform's MessageDigest/CommonCrypto because the SIP simulator's
 * common module must compile on Android, iOS, and JVM identically.
 *
 * Performance: not used in hot paths (only register / re-register), so the
 * straightforward reference implementation is fine.
 */
internal object Md5 {

    private val s = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    private val k = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f
    )

    fun hash(input: ByteArray): ByteArray {
        // Pre-processing: append 0x80, pad with zeros, append original length in bits as 64-bit LE
        val originalLengthBits = input.size.toLong() * 8L
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 8 + i] = (originalLengthBits shr (8 * i)).toByte()
        }

        var a0 = 0x67452301
        var b0 = -0x10325477   // 0xEFCDAB89
        var c0 = -0x67452302   // 0x98BADCFE
        var d0 = 0x10325476

        var offset = 0
        while (offset < paddedLength) {
            val m = IntArray(16)
            for (i in 0 until 16) {
                val o = offset + i * 4
                m[i] = (padded[o].toInt() and 0xFF) or
                    ((padded[o + 1].toInt() and 0xFF) shl 8) or
                    ((padded[o + 2].toInt() and 0xFF) shl 16) or
                    ((padded[o + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val temp = d
                d = c
                c = b
                b = b + leftRotate(a + f + k[i] + m[g], s[i])
                a = temp
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        val out = ByteArray(16)
        intToLeBytes(a0, out, 0)
        intToLeBytes(b0, out, 4)
        intToLeBytes(c0, out, 8)
        intToLeBytes(d0, out, 12)
        return out
    }

    fun hashHex(input: ByteArray): String = bytesToHex(hash(input))

    fun hashHex(input: String): String = hashHex(input.encodeToByteArray())

    private fun leftRotate(x: Int, c: Int): Int = (x shl c) or (x ushr (32 - c))

    private fun intToLeBytes(v: Int, out: ByteArray, off: Int) {
        out[off] = v.toByte()
        out[off + 1] = (v shr 8).toByte()
        out[off + 2] = (v shr 16).toByte()
        out[off + 3] = (v shr 24).toByte()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4])
            sb.append(hex[v and 0x0F])
        }
        return sb.toString()
    }
}
