package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestAuthTest {

    @Test fun parseChallengeBasic() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"3402000000\",nonce=\"abc123\",algorithm=MD5"
        )
        assertEquals("3402000000", challenge.realm)
        assertEquals("abc123", challenge.nonce)
        assertEquals("MD5", challenge.algorithm)
        assertNull(challenge.qop)
    }

    @Test fun parseChallengeWithQop() {
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"R\",nonce=\"N\",algorithm=MD5,qop=\"auth\",opaque=\"O\""
        )
        assertEquals("auth", challenge.qop)
        assertEquals("O", challenge.opaque)
    }

    @Test fun parseChallengeToleratesWhitespace() {
        val challenge = DigestAuth.parseChallenge(
            "Digest  realm = \"R\" , nonce = \"N\" , algorithm = MD5 "
        )
        assertEquals("R", challenge.realm)
        assertEquals("N", challenge.nonce)
    }

    /**
     * RFC 2617 § 3.5 标准向量(无 qop):
     * username="Mufasa", password="Circle Of Life", realm="testrealm@host.com"
     * nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093"
     * uri="/dir/index.html", method="GET"
     * 预期 HA1 = MD5("Mufasa:testrealm@host.com:Circle Of Life") = 939e7578ed9e3c518a452acee763bce9
     * 预期 HA2 = MD5("GET:/dir/index.html") = 39aa3f6e6e8f8f8f...(用 RFC 文本验证)
     */
    @Test fun rfc2617_ha1_basic() {
        val ha1 = Md5.hashHex("Mufasa:testrealm@host.com:Circle Of Life")
        assertEquals("939e7578ed9e3c518a452acee763bce9", ha1)
    }

    @Test fun rfc2617_responseWithoutQop() {
        // 手算 reproduce(无 qop)
        val challenge = DigestAuth.Challenge(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            algorithm = "MD5",
            qop = null,
            opaque = null
        )
        val auth = DigestAuth.buildResponse(
            challenge = challenge,
            username = "Mufasa",
            password = "Circle Of Life",
            method = "GET",
            uri = "/dir/index.html"
        )
        assertTrue(auth.startsWith("Digest "))
        // 手算: HA1 = 939e7578ed9e3c518a452acee763bce9
        //       HA2 = MD5("GET:/dir/index.html")
        //       resp = MD5("HA1:nonce:HA2")
        val ha1 = "939e7578ed9e3c518a452acee763bce9"
        val ha2 = Md5.hashHex("GET:/dir/index.html")
        val expectedResponse = Md5.hashHex("$ha1:${challenge.nonce}:$ha2")
        assertTrue(auth.contains("response=\"$expectedResponse\""))
        assertTrue(auth.contains("uri=\"/dir/index.html\""))
        assertTrue(auth.contains("realm=\"testrealm@host.com\""))
        assertTrue(auth.contains("username=\"Mufasa\""))
        assertTrue(auth.contains("nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
    }

    @Test fun wvpSyle_register401_response() {
        // 模拟真实 WVP 401 challenge
        val challenge = DigestAuth.parseChallenge(
            "Digest realm=\"3402000000\",nonce=\"1234abcd5678efgh\",algorithm=MD5"
        )
        val auth = DigestAuth.buildResponse(
            challenge = challenge,
            username = "34020000001110000001",
            password = "wvp2025!!!",
            method = "REGISTER",
            uri = "sip:34020000002000000001@3402000000"
        )
        // 手算验证
        val ha1 = Md5.hashHex("34020000001110000001:3402000000:wvp2025!!!")
        val ha2 = Md5.hashHex("REGISTER:sip:34020000002000000001@3402000000")
        val expectedResponse = Md5.hashHex("$ha1:1234abcd5678efgh:$ha2")
        assertTrue(auth.contains("response=\"$expectedResponse\""), "Response 应该匹配手算值")
    }

    @Test fun responseWithQopHasNcAndCnonce() {
        val challenge = DigestAuth.Challenge(
            realm = "R", nonce = "N", algorithm = "MD5",
            qop = "auth", opaque = null
        )
        val auth = DigestAuth.buildResponse(
            challenge = challenge,
            username = "u", password = "p",
            method = "REGISTER",
            uri = "sip:x@y",
            cnonce = "abcdef",
            nc = "00000001"
        )
        assertTrue(auth.contains("qop=auth"))
        assertTrue(auth.contains("nc=00000001"))
        assertTrue(auth.contains("cnonce=\"abcdef\""))
        // 手算 qop 模式
        val ha1 = Md5.hashHex("u:R:p")
        val ha2 = Md5.hashHex("REGISTER:sip:x@y")
        val expected = Md5.hashHex("$ha1:N:00000001:abcdef:auth:$ha2")
        assertTrue(auth.contains("response=\"$expected\""))
    }

    @Test fun responseIncludesOpaqueWhenPresent() {
        val challenge = DigestAuth.Challenge(
            realm = "R", nonce = "N", algorithm = "MD5",
            qop = null, opaque = "OPAQUE-VAL"
        )
        val auth = DigestAuth.buildResponse(challenge, "u", "p", "REGISTER", "sip:x@y")
        assertTrue(auth.contains("opaque=\"OPAQUE-VAL\""))
    }
}
