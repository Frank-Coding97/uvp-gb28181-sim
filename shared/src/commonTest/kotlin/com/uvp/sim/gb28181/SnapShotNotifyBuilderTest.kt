package com.uvp.sim.gb28181

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapShotNotifyBuilderTest {

    private val sample = SnapShotNotifyBuilder.build(
        deviceId = "34020000001320000001",
        sn = "42",
        sessionId = "S001",
        snapShotId = "20260617T120000_0",
        timeIso = "2026-06-17T12:00:00.000Z",
        storagePath = "http://192.168.1.10:8088/snap/20260617T120000_0.jpg"
    )

    // T2.1
    @Test
    fun contains_all_six_fields() {
        assertContains(sample, "<SN>42</SN>")
        assertContains(sample, "<DeviceID>34020000001320000001</DeviceID>")
        assertContains(sample, "<SessionID>S001</SessionID>")
        assertContains(sample, "<SnapShotID>20260617T120000_0</SnapShotID>")
        assertContains(sample, "<Time>2026-06-17T12:00:00.000Z</Time>")
        assertContains(sample, "<StoragePath>http://192.168.1.10:8088/snap/20260617T120000_0.jpg</StoragePath>")
    }

    // T2.2
    @Test
    fun encoding_is_gb2312() {
        assertContains(sample, "encoding=\"GB2312\"")
    }

    // T2.3
    @Test
    fun cmdtype_notify_with_subcmd_snapshot() {
        assertContains(sample, "<CmdType>Notify</CmdType>")
        assertContains(sample, "<SubCmd>SnapShot</SubCmd>")
    }

    // T2.4
    @Test
    fun field_order_matches_spec() {
        // SN → DeviceID → SessionID → SnapShotID → Time → StoragePath
        val sn = sample.indexOf("<SN>")
        val devId = sample.indexOf("<DeviceID>")
        val sess = sample.indexOf("<SessionID>")
        val shotId = sample.indexOf("<SnapShotID>")
        val time = sample.indexOf("<Time>")
        val storage = sample.indexOf("<StoragePath>")
        assertTrue(sn < devId, "SN before DeviceID")
        assertTrue(devId < sess, "DeviceID before SessionID")
        assertTrue(sess < shotId, "SessionID before SnapShotID")
        assertTrue(shotId < time, "SnapShotID before Time")
        assertTrue(time < storage, "Time before StoragePath")
    }

    // T2.5
    @Test
    fun line_endings_are_crlf() {
        assertTrue(sample.contains("\r\n"), "must contain CRLF")
        // 确保没有裸 LF(除了 CRLF 中的 LF)
        val strippedCrlf = sample.replace("\r\n", "")
        assertFalse(strippedCrlf.contains('\n'), "no bare LF outside CRLF")
    }

    // T2.6 — buildLegacy fallback
    @Test
    fun build_legacy_uses_cmdtype_snapshot_no_subcmd() {
        val legacy = SnapShotNotifyBuilder.buildLegacy(
            deviceId = "34020000001320000001",
            sn = "42",
            sessionId = "S001",
            snapShotId = "20260617T120000_0",
            timeIso = "2026-06-17T12:00:00.000Z",
            storagePath = "http://h/p.jpg"
        )
        assertContains(legacy, "<CmdType>SnapShot</CmdType>")
        assertFalse(legacy.contains("<SubCmd>"), "legacy must not include SubCmd")
    }

    // 额外:wrapper 顶层 <Notify>
    @Test
    fun wrapper_is_notify() {
        assertContains(sample, "<Notify>")
        assertContains(sample, "</Notify>")
    }
}
