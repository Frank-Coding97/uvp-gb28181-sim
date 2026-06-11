package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals

class Md5Test {

    // RFC 1321 Appendix A.5 标准向量
    @Test fun rfc1321_emptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hashHex(""))
    }

    @Test fun rfc1321_a() {
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hashHex("a"))
    }

    @Test fun rfc1321_abc() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hashHex("abc"))
    }

    @Test fun rfc1321_messageDigest() {
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.hashHex("message digest"))
    }

    @Test fun rfc1321_alphabet() {
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b",
            Md5.hashHex("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test fun rfc1321_alphaNumeric() {
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            Md5.hashHex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        )
    }
}
