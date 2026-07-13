package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T3 — AlarmNotify.buildAlarm GB-2022 §9.5.1 全集 + buildSnapshotAlarm 委托 lock test。
 */
class AlarmNotifyBuilderTest {

    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "test-password"
        )
    )

    private fun payload(
        typeParam: String? = null,
        longitude: Double? = null,
        latitude: Double? = null,
        description: String = "测试报警"
    ) = AlarmPayload(
        deviceId = "34020000001340000001",
        priority = AlarmPriority.EmergencyL2,
        method = AlarmMethod.Video,
        type = AlarmType.VideoLost,
        typeParam = typeParam,
        timeMs = 1_700_000_000_000L,
        description = description,
        longitude = longitude,
        latitude = latitude
    )

    @Test
    fun `输出含 CmdType Alarm`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload())
        assertTrue(xml.contains("<CmdType>Alarm</CmdType>"))
    }

    @Test
    fun `9 字段顺序对齐 spec 9_5_1`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload(longitude = 116.4, latitude = 39.9))
        val order = listOf(
            "<SN>", "<DeviceID>", "<AlarmPriority>", "<AlarmMethod>",
            "<AlarmTime>", "<AlarmDescription>", "<Longitude>", "<Latitude>", "<Info>"
        )
        var prev = -1
        for (tag in order) {
            val idx = xml.indexOf(tag)
            assertTrue(idx > prev, "字段 $tag 顺序错误 (idx=$idx prev=$prev)")
            prev = idx
        }
        assertTrue(xml.contains("<AlarmPriority>2</AlarmPriority>"))
        assertTrue(xml.contains("<AlarmMethod>5</AlarmMethod>"))
    }

    @Test
    fun `Info 内 AlarmType code 正确且 typeParam null 不输出`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload(typeParam = null))
        assertTrue(xml.contains("<AlarmType>1</AlarmType>"))
        assertTrue(!xml.contains("AlarmTypeParam"), "typeParam=null 不应输出 AlarmTypeParam")
    }

    @Test
    fun `typeParam 非空输出且 XML escape 特殊字符`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload(typeParam = "a<b>c&\"d"))
        assertTrue(xml.contains("<AlarmTypeParam>a&lt;b&gt;c&amp;&quot;d</AlarmTypeParam>"))
    }

    @Test
    fun `description 也 escape`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload(description = "门 & 窗 <X>"))
        assertTrue(xml.contains("<AlarmDescription>门 &amp; 窗 &lt;X&gt;</AlarmDescription>"))
    }

    @Test
    fun `经纬度 null 输出空 element`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload(longitude = null, latitude = null))
        assertTrue(xml.contains("<Longitude></Longitude>"))
        assertTrue(xml.contains("<Latitude></Latitude>"))
    }

    @Test
    fun `XML 头 GB2312 且行结尾 CRLF`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload())
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
        assertTrue(!xml.contains("\n\n"), "不应有裸 \\n")
    }

    @Test
    fun `AlarmTime 格式 YYYY-MM-DDTHH-MM-SS`() {
        val xml = AlarmNotify.buildAlarm(config, "5", payload())
        val regex = Regex("<AlarmTime>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}</AlarmTime>")
        assertTrue(regex.containsMatchIn(xml), "AlarmTime 格式不对: $xml")
    }

    /** lock test:buildSnapshotAlarm 委托后稳定输出 — snapshot 默认 method=5/type=5/priority=4。 */
    @Test
    fun `buildSnapshotAlarm 委托后字段锁定`() {
        val xml = AlarmNotify.buildSnapshotAlarm(config, "7")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("<CmdType>Alarm</CmdType>"))
        assertTrue(xml.contains("<SN>7</SN>"))
        assertTrue(xml.contains("<DeviceID>${config.device.alarmChannelId}</DeviceID>"))
        assertTrue(xml.contains("<AlarmPriority>4</AlarmPriority>"))
        assertTrue(xml.contains("<AlarmMethod>5</AlarmMethod>"))
        assertTrue(xml.contains("<AlarmType>5</AlarmType>"))
        assertTrue(xml.contains("<AlarmDescription>Snapshot uploaded</AlarmDescription>"))
        assertTrue(xml.contains("\r\n"))
    }
}
