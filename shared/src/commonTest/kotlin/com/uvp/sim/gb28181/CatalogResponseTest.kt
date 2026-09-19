package com.uvp.sim.gb28181

import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.DirectionType
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.PhotoelectricImagingType
import com.uvp.sim.config.PositionType
import com.uvp.sim.config.PtzType
import com.uvp.sim.config.RoomType
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.SupplyLightType
import com.uvp.sim.config.UseType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 目录应答(§9.3.1)的**双版本形态**测试。
 *
 * ⛔ 这里的断言是「哪一版该有什么、不该有什么」的契约,照 2016 / 2022 附录 A 的 schema
 * 写死。**不要为了让测试变绿而放宽断言** —— 这几条断言正是"兼容 2016"的护栏:
 *  - `PositionType` / `UseType`:2016 有、2022 删 → 2016 支必须有、2022 支必须无
 *  - `BusinessGroupID`:2016 在 `<Info>` 内、2022 提到 `Item` 层 → 位置差异必须守住
 *  - `RoomType`:**1 = 室外、2 = 室内**(本枚举曾写反,这里留回归锚点)
 *  - 共有 5 字段(`PTZType`/`RoomType`/`SupplyLightType`/`DirectionType`/`Resolution`)
 *    2016 支**一个都不能少** —— 不能为了「2022 化」把 2016 也吃的字段一起删掉
 */
class CatalogResponseTest {

    private fun cfg(
        gbVersion: GbVersion = GbVersion.V2022,
        channel: ChannelProfile = ChannelProfile()
    ) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
            channel = channel
        )
    )

    /** 取 `<Info>` 容器内的全部内容(不含容器标签本身);无容器返回 null。 */
    private fun infoBody(xml: String): String? =
        Regex("<Info>(.*?)</Info>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    // ================================ V2022 ================================

    @Test
    fun v2022_wrapsChannelAttributesInInfoContainer() {
        val xml = CatalogResponse.build(cfg(), sn = "5", version = GbVersion.V2022)
        assertTrue(xml.contains("<CmdType>Catalog</CmdType>"))
        assertTrue(xml.contains("<SumNum>1</SumNum>"))
        assertTrue(xml.contains("<DeviceList Num=\"1\">"))
        assertTrue(xml.contains("<DeviceID>34020000001320000001</DeviceID>"))
        assertTrue(xml.contains("<Status>ON</Status>"))

        // ⛔ 关键:通道属性必须在 <Info> 容器**内**,不能平铺在 Item 直接子级
        // (旧实现 buildGb2022Fields() 正是平铺的,两版 schema 都不认)
        val info = infoBody(xml)
        assertTrue(info != null, "V2022 必须输出 <Info> 容器")
        assertTrue(info!!.contains("<PTZType>"), "PTZType 应在 <Info> 内")
        assertTrue(info.contains("<RoomType>"))
        assertTrue(info.contains("<SupplyLightType>"))
        assertTrue(info.contains("<DirectionType>"))
        assertTrue(info.contains("<Resolution>"))
    }

    @Test
    fun v2022_emitsOnlyItsOwnExtras() {
        val ch = ChannelProfile(
            ptzType = PtzType.Dome,
            roomType = RoomType.Indoor,
            supplyLightType = SupplyLightType.Infrared,
            directionType = DirectionType.Southeast,
            resolution = "1920*1080",
            photoelectricImagingTypes = listOf(
                PhotoelectricImagingType.VisibleLight,
                PhotoelectricImagingType.Thermal,
            ),
            streamNumberList = "0/1",
        )
        val xml = CatalogResponse.build(cfg(channel = ch), sn = "1", version = GbVersion.V2022)
        val info = infoBody(xml)!!

        // —— 两版共有,值按 2022 附录 A ——
        assertTrue(info.contains("<PTZType>1</PTZType>"), "Dome=1")
        assertTrue(info.contains("<RoomType>2</RoomType>"), "⛔ Indoor=2(标准:1-室外 / 2-室内)")
        assertTrue(info.contains("<SupplyLightType>2</SupplyLightType>"), "Infrared=2")
        assertTrue(info.contains("<DirectionType>5</DirectionType>"), "Southeast=5")
        assertTrue(info.contains("<Resolution>1920*1080</Resolution>"))

        // —— 2022 独有 ——
        assertTrue(
            info.contains("<PhotoelectricImagingType>1/2</PhotoelectricImagingType>"),
            "光电成像类型可多值,用 / 分割"
        )
        assertTrue(info.contains("<StreamNumberList>0/1</StreamNumberList>"))

        // —— ⛔ 2022 已删除这两字段,不得出现在 2022 报文里 ——
        assertFalse(info.contains("<PositionType>"), "2022 已删除 PositionType,不得输出")
        assertFalse(info.contains("<UseType>"), "2022 已删除 UseType,不得输出")
    }

    @Test
    fun v2022_putsBusinessGroupIdOnItemLevel() {
        val ch = ChannelProfile(businessGroupId = "34020000002150000001")
        val xml = CatalogResponse.build(cfg(channel = ch), sn = "1", version = GbVersion.V2022)
        val info = infoBody(xml)!!

        assertTrue(
            xml.contains("<BusinessGroupID>34020000002150000001</BusinessGroupID>"),
            "2022 的 BusinessGroupID 应在 Item 层"
        )
        assertFalse(
            info.contains("<BusinessGroupID>"),
            "2022 的 BusinessGroupID 是 Item 层字段,不在 <Info> 内"
        )
    }

    @Test
    fun v2022_keepsIpAndPortOnItemLevel() {
        val ch = ChannelProfile(ipAddress = "192.168.1.100", port = 5061)
        val xml = CatalogResponse.build(cfg(channel = ch), sn = "1", version = GbVersion.V2022)
        val info = infoBody(xml)!!

        assertTrue(xml.contains("<IPAddress>192.168.1.100</IPAddress>"))
        assertTrue(xml.contains("<Port>5061</Port>"))
        assertFalse(info.contains("<IPAddress>"), "IPAddress 是 Item 层字段,不在 <Info> 内")
        assertFalse(info.contains("<Port>"), "Port 是 Item 层字段,不在 <Info> 内")
    }

    @Test
    fun v2022_skipsPlaceholderIpAddress() {
        // ChannelProfile 默认 ipAddress = "0.0.0.0" 是占位值,不是有效地址 → 不应发给平台
        val xml = CatalogResponse.build(cfg(), sn = "1", version = GbVersion.V2022)
        assertFalse(xml.contains("<IPAddress>0.0.0.0</IPAddress>"))
    }

    @Test
    fun v2022_skipsIllegalPtzTypeZero() {
        // 两版标准的 PTZType 合法值域都从 1 起(2016:1-4 / 2022:1-7),
        // Unsupported(0) 是模拟器内部占位值 → 不输出该元素
        val ch = ChannelProfile(ptzType = PtzType.Unsupported)
        val xml = CatalogResponse.build(cfg(channel = ch), sn = "1", version = GbVersion.V2022)
        val info = infoBody(xml)!!
        assertFalse(info.contains("<PTZType>"), "PTZType=0 非法,整条省略")
    }

    // ================================ V2016 ================================

    @Test
    fun v2016_emitsItsOwnFieldSet() {
        val ch = ChannelProfile(
            ptzType = PtzType.RemoteGun,
            positionType = PositionType.Checkpoint,
            roomType = RoomType.Outdoor,
            useType = UseType.PublicSecurity,
            supplyLightType = SupplyLightType.White,
            directionType = DirectionType.North,
            resolution = "1280*720",
            businessGroupId = "34020000002150000001",
        )
        val xml = CatalogResponse.build(
            cfg(gbVersion = GbVersion.V2016, channel = ch),
            sn = "1",
            version = GbVersion.V2016,
        )
        // 2016 附录 A(标准页 52-53)同样定义 <Info> 容器,字段就在里面
        val info = infoBody(xml) ?: error("V2016 也应有 <Info> 容器")

        // —— 两版共有 ——
        assertTrue(info.contains("<PTZType>4</PTZType>"), "RemoteGun=4")
        assertTrue(info.contains("<RoomType>1</RoomType>"), "⛔ Outdoor=1(标准:1-室外 / 2-室内)")
        assertTrue(info.contains("<SupplyLightType>3</SupplyLightType>"), "White=3")
        assertTrue(info.contains("<DirectionType>4</DirectionType>"), "North=4")
        assertTrue(info.contains("<Resolution>1280*720</Resolution>"))

        // —— ⭐ 2016 独有:2022 删了这两个,2016 支必须保留 ——
        assertTrue(info.contains("<PositionType>1</PositionType>"), "Checkpoint=1")
        assertTrue(info.contains("<UseType>1</UseType>"), "PublicSecurity=1")

        // —— ⭐ 位置差异:2016 的 BusinessGroupID 仍在 <Info> 内 ——
        assertTrue(
            info.contains("<BusinessGroupID>34020000002150000001</BusinessGroupID>"),
            "2016 的 BusinessGroupID 在 <Info> 内(2022 才提到 Item 层)"
        )

        // —— 2016 没有 2022 那批新增字段 ——
        assertFalse(info.contains("<PhotoelectricImagingType>"))
        assertFalse(info.contains("<CapturePositionType>"))
        assertFalse(info.contains("<StreamNumberList>"))
    }

    @Test
    fun v2016_keepsSharedFieldsThat2022AlsoHas() {
        // ⭐ 回归锚点:修 2022 形态时最容易误伤的就是这 5 个"两版共有"的字段 ——
        // 它们 2016 也有、位置也一样,2016 支一个都不能少。
        val xml = CatalogResponse.build(
            cfg(gbVersion = GbVersion.V2016),
            sn = "1",
            version = GbVersion.V2016,
        )
        val info = infoBody(xml)!!
        assertTrue(info.contains("<PTZType>"), "2016 也有 PTZType(值域 1-4)")
        assertTrue(info.contains("<RoomType>"))
        assertTrue(info.contains("<SupplyLightType>"))
        assertTrue(info.contains("<DirectionType>"))
        assertTrue(info.contains("<Resolution>"))
    }

    @Test
    fun v2016_doesNotEmitItemLevelBusinessGroupId() {
        // 位置差异的反向守卫:2016 不该把 BusinessGroupID 放到 Item 层
        // (标准里 2016 的 Item 没有这个直接子元素)
        val ch = ChannelProfile(businessGroupId = "34020000002150000001")
        val xml = CatalogResponse.build(
            cfg(gbVersion = GbVersion.V2016, channel = ch),
            sn = "1",
            version = GbVersion.V2016,
        )
        val beforeInfo = xml.substringBefore("<Info>")
        assertFalse(
            beforeInfo.contains("<BusinessGroupID>"),
            "2016 的 BusinessGroupID 不在 Item 层"
        )
    }

    // ================================ 其它 ================================

    @Test
    fun build_manufacturer_readsFromConfig() {
        val custom = cfg().run {
            copy(device = device.copy(manufacturer = "海康威视", model = "DS-IPC-Mock"))
        }
        val xml = CatalogResponse.build(custom, sn = "1", version = GbVersion.V2022)
        assertTrue(xml.contains("<Manufacturer>海康威视</Manufacturer>"))
        assertTrue(xml.contains("<Model>DS-IPC-Mock</Model>"))
    }

    @Test
    fun build_declaresUtf8AndCrlfAsIntermediateForm() {
        // ⭐ 这里断言的是 builder 产出的**中间串**形状:声明 UTF-8 + CRLF 换行。
        // 出口处由 I-1 的 encodeSignalingBody 按有效版本把声明与字节一起改写为
        // GB18030(2022)/ GB2312(2016) —— 那是字符集层的职责,不在本测试范围。
        val xml = CatalogResponse.build(cfg(), sn = "1", version = GbVersion.V2022)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("\r\n"))
    }
}
