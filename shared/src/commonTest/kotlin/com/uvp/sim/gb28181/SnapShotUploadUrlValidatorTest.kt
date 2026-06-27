package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-6 (audit §3) — SnapShotUploadUrlValidator 严格校验测试。
 *
 * 覆盖:
 *  - 合法 URL(http/https + host 在 allowList)
 *  - scheme 拒绝(ftp / ws / 空)
 *  - host 拒绝(不在 allowList / allowList 空)
 *  - loopback 拒绝(127.0.0.0/8, ::1)
 *  - link-local 拒绝(169.254.0.0/16, fe80::/10)
 *  - multicast 拒绝(224.0.0.0/4, ff00::/8)
 *  - 元数据字面量拒绝(localhost, 0.0.0.0, metadata.google.internal 等)
 *  - IPv6 格式解析([::1], [fe80::1])
 */
class SnapShotUploadUrlValidatorTest {

    // T1 — 合法:http + host 在 allowList
    @Test
    fun accepts_http_host_in_allow_list() {
        val allowList = listOf("192.168.1.10", "platform.example.com")
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://192.168.1.10:8088/snap/",
                allowList
            )
        )
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://platform.example.com/upload",
                allowList
            )
        )
    }

    // T2 — 合法:https + host 在 allowList
    @Test
    fun accepts_https_host_in_allow_list() {
        val allowList = listOf("secure.example.com")
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "https://secure.example.com/upload",
                allowList
            )
        )
    }

    // T3 — 拒绝:scheme 非 http/https
    @Test
    fun rejects_non_http_scheme() {
        val allowList = listOf("host.com")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "ftp://host.com/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "ws://host.com/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "file:///tmp/test",
                allowList
            )
        )
    }

    // T4 — 拒绝:allowList 空(零信任默认)
    @Test
    fun rejects_any_url_when_allow_list_empty() {
        val emptyList = emptyList<String>()
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://192.168.1.10/snap/",
                emptyList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "https://example.com/upload",
                emptyList
            )
        )
    }

    // T5 — 拒绝:host 不在 allowList
    @Test
    fun rejects_host_not_in_allow_list() {
        val allowList = listOf("allowed.com")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://notallowed.com/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://192.168.1.99/path",
                allowList
            )
        )
    }

    // T6 — 拒绝:loopback IPv4 (127.0.0.0/8)
    @Test
    fun rejects_ipv4_loopback() {
        val allowList = listOf("127.0.0.1", "127.1.2.3") // 即使在 allowList 也拒
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://127.0.0.1/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://127.1.2.3/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://127.255.255.255/path",
                allowList
            )
        )
    }

    // T7 — 拒绝:link-local IPv4 (169.254.0.0/16)
    @Test
    fun rejects_ipv4_link_local() {
        val allowList = listOf("169.254.169.254", "169.254.0.1")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://169.254.169.254/latest/meta-data/",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://169.254.0.1/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://169.254.255.255/path",
                allowList
            )
        )
    }

    // T8 — 拒绝:multicast IPv4 (224.0.0.0/4)
    @Test
    fun rejects_ipv4_multicast() {
        val allowList = listOf("224.0.0.1", "239.255.255.255")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://224.0.0.1/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://239.255.255.255/path",
                allowList
            )
        )
    }

    // T9 — 拒绝:0.0.0.0 wildcard
    @Test
    fun rejects_ipv4_wildcard() {
        val allowList = listOf("0.0.0.0")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://0.0.0.0/path",
                allowList
            )
        )
    }

    // T10 — 拒绝:localhost 字面量
    @Test
    fun rejects_localhost_literal() {
        val allowList = listOf("localhost")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://localhost/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://LOCALHOST/path",
                allowList
            )
        )
    }

    // T11 — 拒绝:云厂商元数据地址字面量
    @Test
    fun rejects_metadata_literals() {
        val allowList = listOf(
            "metadata.google.internal",
            "metadata",
            "metadata.azure.com"
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://metadata.google.internal/computeMetadata/",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://metadata/latest/",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://metadata.azure.com/metadata/",
                allowList
            )
        )
    }

    // T12 — 拒绝:IPv6 loopback (::1)
    @Test
    fun rejects_ipv6_loopback() {
        val allowList = listOf("::1")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[::1]/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[::1]:8080/path",
                allowList
            )
        )
    }

    // T13 — 拒绝:IPv6 link-local (fe80::/10)
    @Test
    fun rejects_ipv6_link_local() {
        val allowList = listOf("fe80::1", "fe80::abcd:1234")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[fe80::1]/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[fe80::abcd:1234]/path",
                allowList
            )
        )
    }

    // T14 — 拒绝:IPv6 multicast (ff00::/8)
    @Test
    fun rejects_ipv6_multicast() {
        val allowList = listOf("ff02::1", "ff05::2")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[ff02::1]/path",
                allowList
            )
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[ff05::2]/path",
                allowList
            )
        )
    }

    // T15 — 拒绝:IPv6 unspecified (::)
    @Test
    fun rejects_ipv6_unspecified() {
        val allowList = listOf("::")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://[::]/path",
                allowList
            )
        )
    }

    // T16 — 合法:私有 IPv4 (10.0.0.0/8 / 192.168.0.0/16)不在危险列表,只需在 allowList
    @Test
    fun accepts_private_ipv4_in_allow_list() {
        val allowList = listOf("10.0.0.5", "192.168.1.10", "172.16.0.1")
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://10.0.0.5/path",
                allowList
            )
        )
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://192.168.1.10:8088/snap/",
                allowList
            )
        )
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://172.16.0.1/upload",
                allowList
            )
        )
    }

    // T17 — host 提取:带端口 / 路径 / 查询
    @Test
    fun extracts_host_correctly() {
        val allowList = listOf("example.com")
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://example.com:8080/path?query=1",
                allowList
            )
        )
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "https://example.com/",
                allowList
            )
        )
        assertTrue(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict(
                "http://example.com",
                allowList
            )
        )
    }

    // T18 — 边界:空 URL / 畸形 URL
    @Test
    fun rejects_malformed_urls() {
        val allowList = listOf("host.com")
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict("", allowList)
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict("http://", allowList)
        )
        assertFalse(
            SnapShotUploadUrlValidator.isValidUploadUrlStrict("not-a-url", allowList)
        )
    }
}
