package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 A.2.6.15 PTZ 精准状态查询应答（请求侧 A.2.4.13）。
 *
 * ⭐ 2026-09-19 本测试整体重写 —— 原先断言的两件事**都是错的**：
 *  1. 断言 `CmdType=PTZPreciseStatusQuery`（两版标准全文 0 命中）→ 现在断言 `PTZPosition`；
 *  2. 只断言 Pan/Tilt/Zoom 三个字段 → 现在断言**六个**（A.2.6.15 还有三个光学量）。
 * 也就是说旧测试是把 bug 固化成了"预期"，这也是它没能在回归中拦住本次两个缺口的原因。
 */
class PtzPreciseStatusResponseTest {

    private val cfg = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password"
        )
    )

    private fun snapshot(
        pan: Double = 45.5,
        tilt: Double = -10.0,
        zoom: Double = 3.0,
    ) = PtzPositionSnapshot(
        pan = pan,
        tilt = tilt,
        zoom = zoom,
        horizontalFieldAngle = 20.0,
        verticalFieldAngle = 12.0,
        maxViewDistance = 900.0,
    )

    @Test
    fun build_completePose() {
        val xml = PtzPreciseStatusResponse.build(
            config = cfg,
            sn = "9",
            channelId = "ch001",
            position = snapshot()
        )
        assertTrue(xml.contains("<SN>9</SN>"))
        assertTrue(xml.contains("<DeviceID>ch001</DeviceID>"))
        assertTrue(xml.contains("<Pan>45.50</Pan>"))
        assertTrue(xml.contains("<Tilt>-10.00</Tilt>"))
        assertTrue(xml.contains("<Zoom>3.00</Zoom>"))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"GB2312\"?>"))
        assertTrue(xml.contains("\r\n"))
    }

    // ---- G-3 修正点 1：CmdType ----

    /**
     * ⛔ 应答 `CmdType` 必须与请求（A.2.4.13）**同名** = `PTZPosition`。
     * 旧的 `PTZPreciseStatusQuery` 在两版标准里都是 0 命中，平台按标准发查询会**无人受理**。
     */
    @Test
    fun cmdType_is_PTZPosition_matchingTheQuery() {
        val xml = PtzPreciseStatusResponse.build(cfg, "1", "ch001", snapshot())
        assertTrue(
            xml.contains("<CmdType>PTZPosition</CmdType>"),
            "A.2.6.15 的 CmdType 必须是 PTZPosition，实际: $xml"
        )
        assertFalse(
            xml.contains("PTZPreciseStatusQuery"),
            "自造名不得再出现在报文里: $xml"
        )
    }

    /**
     * 单一真源锚点：应答侧**必须**引用 [PtzPositionMessage.CMD_TYPE]，
     * 这样"请求/应答/通知"三处永远同名。若有人把常量改回自造名，这一条会立刻红。
     */
    @Test
    fun cmdType_comesFromTheSingleSourceOfTruth() {
        assertEquals("PTZPosition", PtzPositionMessage.CMD_TYPE)
        val xml = PtzPreciseStatusResponse.build(cfg, "1", "ch001", snapshot())
        assertTrue(xml.contains("<CmdType>${PtzPositionMessage.CMD_TYPE}</CmdType>"))
    }

    // ---- G-3 修正点 2：六个字段而非三个 ----

    /**
     * A.2.6.15 除三轴姿态外还有 `HorizontalFieldAngle` / `VerticalFieldAngle` /
     * `MaxViewDistance`。原先一条都不发 —— 平台拿不到视场角就没法把"框选像素"
     * 换算成云台角度，目标跟踪的框选因此无法落到设备上。
     */
    @Test
    fun build_emitsAllSixA22615Fields() {
        val xml = PtzPreciseStatusResponse.build(
            config = cfg, sn = "2", channelId = "ch001",
            position = snapshot(pan = 45.5, tilt = -10.0, zoom = 3.0)
        )
        assertTrue(xml.contains("<Pan>45.50</Pan>"))
        assertTrue(xml.contains("<Tilt>-10.00</Tilt>"))
        assertTrue(xml.contains("<Zoom>3.00</Zoom>"))
        assertTrue(xml.contains("<HorizontalFieldAngle>20.00</HorizontalFieldAngle>"))
        assertTrue(xml.contains("<VerticalFieldAngle>12.00</VerticalFieldAngle>"))
        assertTrue(xml.contains("<MaxViewDistance>900.00</MaxViewDistance>"))
        // 顺序：A.2.6.15 是 Pan→Tilt→Zoom→再三个光学量
        assertTrue(xml.indexOf("<Zoom>") < xml.indexOf("<HorizontalFieldAngle>"))
        assertTrue(xml.indexOf("<HorizontalFieldAngle>") < xml.indexOf("<VerticalFieldAngle>"))
        assertTrue(xml.indexOf("<VerticalFieldAngle>") < xml.indexOf("<MaxViewDistance>"))
    }

    @Test
    fun build_blankChannelId_fallsBackToDeviceId() {
        val xml = PtzPreciseStatusResponse.build(
            config = cfg,
            sn = "1",
            channelId = "",
            position = snapshot(pan = 0.0, tilt = 0.0, zoom = 1.0)
        )
        assertTrue(xml.contains("<DeviceID>34020000001110000001</DeviceID>"))
        assertTrue(xml.contains("<Pan>0.00</Pan>"))
        assertTrue(xml.contains("<Zoom>1.00</Zoom>"))
    }

    /**
     * 负数与小数位进位。
     *
     * ⚠️ 刻意**不**断言 `.005` 这种"恰好半格"的取值 —— `kotlin.math.round` 的平局规则
     * （ties-to-even）不是本函数要保证的语义，钉住它只会让测试在换 stdlib 时莫名其妙地红。
     * 这里只钉三条有确定结论的：向上进位、负向进位、以及**不出现 `-0.00`**。
     */
    @Test
    fun formatTwo_handlesRoundingAndNegativeZero() {
        assertEquals("1.00", PtzPreciseStatusResponse.formatTwo(0.999), "0.999 进位到 1.00")
        assertEquals("-1.00", PtzPreciseStatusResponse.formatTwo(-0.999), "-0.999 进位到 -1.00")
        assertEquals("0.00", PtzPreciseStatusResponse.formatTwo(-0.004), "绝不允许输出 -0.00")
    }
}
