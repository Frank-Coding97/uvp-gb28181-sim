package com.uvp.sim.gb28181

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.GbVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 目录应答里的**安装位置**(`<Longitude>` / `<Latitude>`)。
 *
 * 这条通路的现实意义:平台侧 `gb_channel.longitude/latitude` 只有三个写入口,目录应答是其中
 * "设备自己声明装在哪"的那一路。本模拟器 2026-09-21 之前**从不输出这两个元素**,导致
 * 平台上通道坐标永远是 0、界面只能显示"无坐标"。本组用例把这条通路钉住。
 *
 * ⛔ 每条用例都在验"边界"而不是"有输出":元素位置(标准里排在 `<Status>` 之后)、
 * 节点类型白名单、零值不输出、成对输出 —— 这四条错任何一条,平台那边都是静默错。
 */
class CatalogInstallPositionTest {

    private val deviceId = "34020000001110000001"
    private val channelId = "34020000001320000001"

    private fun deviceNode() = CatalogNode(deviceId, CatalogNodeType.Device, "Cam", deviceId)

    private fun channelNode(fields: Map<String, String> = emptyMap()) =
        CatalogNode(channelId, CatalogNodeType.VideoChannel, "V1", deviceId, fields)

    private fun build2022(channel: ChannelProfile?) = CatalogNotifyBuilder.build(
        deviceId = deviceId,
        sn = 1,
        tree = listOf(deviceNode(), channelNode()),
        version = GbVersion.V2022,
        channel = channel,
    )

    /** 取某个 `<Tag>` 元素的值,不存在返回 null。 */
    private fun elementOf(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)

    /**
     * 报文是 `\r\n` 换行的(SIP 里必须如此),但按行切片的断言写 `\n` 更清楚。
     * 不归一化的话 `substringAfter("<Item>\n…")` 会**静默失配**并返回整串,
     * 断言变成"在整份报文里找" —— 那种测试即使功能坏了也会通过。
     */
    private fun lf(xml: String) = xml.replace("\r\n", "\n")

    @Test
    fun `视频通道按 ChannelProfile 声明安装位置`() {
        val xml = build2022(ChannelProfile())
        assertEquals("116.404", elementOf(xml, "Longitude"))
        assertEquals("39.915", elementOf(xml, "Latitude"))
    }

    @Test
    fun `安装位置排在 Status 之后、Info 之前（两版标准位置相同）`() {
        val xml = lf(build2022(ChannelProfile()))
        val item = xml.substringAfter("<Item>\n<DeviceID>$channelId</DeviceID>")
        assertFalse(item.startsWith("<Item>"), "切片失配:该用例会退化成在整份报文里找元素")
        val statusEnd = item.indexOf("</Status>")
        val longitude = item.indexOf("<Longitude>")
        val infoStart = item.indexOf("<Info>")
        assertTrue(statusEnd >= 0 && longitude >= 0 && infoStart >= 0)
        assertTrue(longitude > statusEnd, "Longitude 必须在 Status 之后")
        assertTrue(longitude < infoStart, "Longitude 必须在 Info 之前")
    }

    @Test
    fun `2016 与 2022 都输出安装位置`() {
        val v2016 = CatalogNotifyBuilder.build(
            deviceId, 1, listOf(channelNode()), version = GbVersion.V2016, channel = ChannelProfile(),
        )
        assertEquals("116.404", elementOf(v2016, "Longitude"))
        assertEquals("39.915", elementOf(v2016, "Latitude"))
    }

    @Test
    fun `节点级字段优先于 ChannelProfile`() {
        // 多通道各有各的安装位置时,节点的显式声明必须赢 —— 否则所有通道都会落到同一个点上。
        val xml = CatalogNotifyBuilder.build(
            deviceId, 1,
            listOf(channelNode(mapOf("Longitude" to "121.4737", "Latitude" to "31.2304"))),
            version = GbVersion.V2022,
            channel = ChannelProfile(),
        )
        assertEquals("121.4737", elementOf(xml, "Longitude"))
        assertEquals("31.2304", elementOf(xml, "Latitude"))
    }

    @Test
    fun `坐标为 0 时不输出这两个元素`() {
        // 0 在本仓全链路表示"未声明"。发 0 的后果是平台判"设备没报"而不落库,
        // 白白多两个元素并把严格 XSD 校验拖下水。
        val xml = build2022(ChannelProfile(longitude = 0.0, latitude = 0.0))
        assertFalse(xml.contains("<Longitude>"), "0 不该发 Longitude")
        assertFalse(xml.contains("<Latitude>"), "0 不该发 Latitude")
    }

    @Test
    fun `只声明一半也不输出 —— 绝不发半对坐标`() {
        // 只给经度:平台会把"经度是新的、纬度还是上一轮的值"这种状态写进库,
        // 谁也解释不了。宁可两个都不发。
        val onlyLongitude = build2022(ChannelProfile(longitude = 116.404, latitude = 0.0))
        assertFalse(onlyLongitude.contains("<Longitude>"))
        assertFalse(onlyLongitude.contains("<Latitude>"))

        val onlyLatitude = build2022(ChannelProfile(longitude = 0.0, latitude = 39.915))
        assertFalse(onlyLatitude.contains("<Longitude>"))
        assertFalse(onlyLatitude.contains("<Latitude>"))
    }

    @Test
    fun `Device 节点不吃 channel 兜底 —— 安装位置是通道级属性`() {
        // channel 参数只是"通道属性兜底",不该顺手给设备节点也盖一个位置;
        // 设备位置只能靠该节点自己的 fields 显式声明。
        val xml = lf(build2022(ChannelProfile()))
        val deviceItem = xml.substringAfter("<Item>\n<DeviceID>$deviceId</DeviceID>").substringBefore("</Item>")
        assertFalse(deviceItem.contains("<Longitude>"), "设备节点不该借用通道的安装位置")
    }

    @Test
    fun `Device 节点可用 fields 显式声明自己的位置`() {
        val node = CatalogNode(
            deviceId, CatalogNodeType.Device, "Cam", deviceId,
            mapOf("Longitude" to "113.2644", "Latitude" to "23.1291"),
        )
        val xml = CatalogNotifyBuilder.build(deviceId, 1, listOf(node), version = GbVersion.V2022)
        assertEquals("113.2644", elementOf(xml, "Longitude"))
        assertEquals("23.1291", elementOf(xml, "Latitude"))
    }

    @Test
    fun `非设备类节点一律不输出安装位置`() {
        // 标准原文是「当为设备时,经度」。系统 / 业务分组 / 虚拟组织 / 行政区划 / 报警通道
        // 都没有安装位置可言,给它们发坐标是报文层面的越界。
        for (type in listOf(
            CatalogNodeType.System,
            CatalogNodeType.BusinessGroup,
            CatalogNodeType.VirtualOrg,
            CatalogNodeType.AdministrativeRegion,
            CatalogNodeType.AlarmChannel,
        )) {
            val node = CatalogNode("34020000001320000099", type, "N", deviceId)
            val xml = CatalogNotifyBuilder.build(
                deviceId, 1, listOf(node), version = GbVersion.V2022, channel = ChannelProfile(),
            )
            assertFalse(xml.contains("<Longitude>"), "$type 不该输出 Longitude")
            assertFalse(xml.contains("<Latitude>"), "$type 不该输出 Latitude")
        }
    }

    @Test
    fun `坐标用定点小数、去掉尾部 0、不出现科学计数法`() {
        // 形态恒定是刻意的:输入来自用户可编辑字段,不该因为取值大小不同而换写法。
        val cases = mapOf(
            116.404 to "116.404",
            39.915 to "39.915",
            -122.4194 to "-122.4194",
            0.000001 to "0.000001",
            116.1234567 to "116.123457", // 第 7 位四舍五入
        )
        for ((value, expected) in cases) {
            val xml = build2022(ChannelProfile(longitude = value, latitude = 39.915))
            assertEquals(expected, elementOf(xml, "Longitude"), "经度 $value 的报文写法")
            assertFalse(xml.contains("E-"), "不该出现科学计数法")
        }
    }

    @Test
    fun `增量 NOTIFY 的 ADD 与 UPDATE 也带安装位置`() {
        // 平台"刷新目录"走的是全量,但设备侧改配置后推的是增量 ——
        // 两条路都要能带坐标,否则改名这类小改动会把坐标通路漏掉。
        for (event in listOf(
            CatalogChangeEvent.Add(channelNode()),
            CatalogChangeEvent.Update(channelNode()),
        )) {
            val xml = CatalogNotifyBuilder.buildIncremental(
                deviceId, 2, listOf(event), version = GbVersion.V2022, channel = ChannelProfile(),
            )
            assertEquals("116.404", elementOf(xml, "Longitude"), "$event 缺 Longitude")
            assertEquals("39.915", elementOf(xml, "Latitude"), "$event 缺 Latitude")
        }
    }

    @Test
    fun `通道在线状态变更的简版 NOTIFY 不带安装位置`() {
        // buildStatusOnly 的契约就是"Item 只有 DeviceID + Event + Status"——
        // 它不是目录刷新,带上坐标就把"轻量"这个设计意图破坏了。
        val xml = CatalogNotifyBuilder.buildStatusOnly(deviceId, 3, channelId, online = true)
        assertFalse(xml.contains("<Longitude>"))
        assertFalse(xml.contains("<Latitude>"))
    }
}
