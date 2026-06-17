package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SnapShotConfigParserTest {

    private fun xmlOf(
        sessionId: String? = "S001",
        uploadUrl: String? = "http://192.168.1.10:8088/snap/",
        snapNum: String? = "3",
        interval: String? = "2"
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\n")
        append("<Control>\n")
        append("<CmdType>DeviceControl</CmdType>\n")
        append("<SN>17</SN>\n")
        append("<DeviceID>34020000001320000001</DeviceID>\n")
        append("<SnapShotConfig>\n")
        sessionId?.let { append("<SessionID>").append(it).append("</SessionID>\n") }
        uploadUrl?.let { append("<UploadURL>").append(it).append("</UploadURL>\n") }
        snapNum?.let { append("<SnapNum>").append(it).append("</SnapNum>\n") }
        interval?.let { append("<Interval>").append(it).append("</Interval>\n") }
        append("</SnapShotConfig>\n")
        append("</Control>\n")
    }

    // T1.1
    @Test
    fun parses_full_config() {
        val cfg = SnapShotConfigParser.parse(xmlOf())
        assertNotNull(cfg)
        assertEquals("S001", cfg.sessionId)
        assertEquals("http://192.168.1.10:8088/snap/", cfg.uploadUrl)
        assertEquals(3, cfg.snapNum)
        assertEquals(2000L, cfg.intervalMs)
    }

    // T1.2
    @Test
    fun returns_null_when_session_id_missing() {
        assertNull(SnapShotConfigParser.parse(xmlOf(sessionId = null)))
    }

    // T1.3
    @Test
    fun returns_null_when_upload_url_missing() {
        assertNull(SnapShotConfigParser.parse(xmlOf(uploadUrl = null)))
    }

    // T1.4
    @Test
    fun snap_num_defaults_to_one_when_missing() {
        val cfg = SnapShotConfigParser.parse(xmlOf(snapNum = null))
        assertNotNull(cfg)
        assertEquals(1, cfg.snapNum)
    }

    // T1.5
    @Test
    fun snap_num_clamped_to_max_ten() {
        val cfg = SnapShotConfigParser.parse(xmlOf(snapNum = "999"))
        assertNotNull(cfg)
        assertEquals(10, cfg.snapNum)
    }

    // T1.6
    @Test
    fun interval_seconds_converted_to_ms() {
        val cfg = SnapShotConfigParser.parse(xmlOf(interval = "5"))
        assertNotNull(cfg)
        assertEquals(5000L, cfg.intervalMs)
    }

    // T1.7
    @Test
    fun rejects_non_http_scheme() {
        assertNull(SnapShotConfigParser.parse(xmlOf(uploadUrl = "ftp://host/path")))
    }

    // T1.8
    @Test
    fun rejects_url_with_empty_host() {
        assertNull(SnapShotConfigParser.parse(xmlOf(uploadUrl = "http://")))
    }

    // 额外:interval 缺省 0
    @Test
    fun interval_defaults_to_zero_when_missing() {
        val cfg = SnapShotConfigParser.parse(xmlOf(interval = null))
        assertNotNull(cfg)
        assertEquals(0L, cfg.intervalMs)
    }

    // 额外:snap_num 下限 1
    @Test
    fun snap_num_clamped_to_min_one() {
        val cfg = SnapShotConfigParser.parse(xmlOf(snapNum = "0"))
        assertNotNull(cfg)
        assertEquals(1, cfg.snapNum)
    }

    // 额外:interval 上限 60s
    @Test
    fun interval_clamped_to_max_60s() {
        val cfg = SnapShotConfigParser.parse(xmlOf(interval = "120"))
        assertNotNull(cfg)
        assertEquals(60_000L, cfg.intervalMs)
    }

    // 额外:HTTPS 也接受
    @Test
    fun accepts_https() {
        val cfg = SnapShotConfigParser.parse(xmlOf(uploadUrl = "https://host:443/p"))
        assertNotNull(cfg)
        assertEquals("https://host:443/p", cfg.uploadUrl)
    }
}
