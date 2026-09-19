package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 附录 I 的直接后果:同一份设备配置,在「有效版本」不同时必须产出不同形态的应答。
 *
 * 这里覆盖的是**平台版本更低**的场景 —— 设备自己声明 2022,但对面平台只声明 2.0,
 * 于是 min(本机, 平台) = 2016,应答里就不该出现 2022 才有的字段/结构。
 * 这正是修复前的缺口:三个构造器原先一律读 `config.gbVersion`(本机声明),平台是 2016 时
 * 仍会收到它识别不了的东西。
 */
class ResponseVersionOverrideTest {

    private fun cfg(gbVersion: GbVersion = GbVersion.V2022) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
            hardwareVersion = "Mobile",
        ),
    )

    @Test
    fun `DeviceInfo 的有效版本是 2016 时不下发 HardwareVersion`() {
        val c = cfg()
        assertTrue(
            DeviceInfoResponse.build(c, sn = "1", gbVersion = GbVersion.V2022)
                .contains("<HardwareVersion>"),
            "2022 形态应带 HardwareVersion",
        )
        assertFalse(
            DeviceInfoResponse.build(c, sn = "1", gbVersion = GbVersion.V2016)
                .contains("<HardwareVersion>"),
            "平台只支持 2016 时不该出现 HardwareVersion",
        )
    }

    @Test
    fun `DeviceStatus 的有效版本是 2016 时属性名换成小写 num`() {
        val snapshot = DeviceStatusSnapshot(
            online = true,
            deviceTime = "2026-09-17T20:00:00",
            recording = false,
            alarming = true,
            guarded = false,
        )
        val modern = DeviceStatusResponse.build(cfg(), sn = "1", snapshot = snapshot, gbVersion = GbVersion.V2022)
        val legacy = DeviceStatusResponse.build(cfg(), sn = "1", snapshot = snapshot, gbVersion = GbVersion.V2016)

        // ⛔ 2016 与 2022 **同构**（都是嵌套 Alarmstatus + Item/DutyStatus），
        //    唯一差异是属性名大小写。原先这里断言 2016 回"扁平 <AlarmStatus>1</AlarmStatus>"
        //    是编的 —— 那个元素名在两版标准里都是 0 命中。
        assertTrue(modern.contains("<Alarmstatus Num=\"1\">"), "2022 形态: 大写 Num 属性")
        assertTrue(modern.contains("<DutyStatus>ALARM</DutyStatus>"))

        assertTrue(legacy.contains("<Alarmstatus num=\"1\">"), "2016 形态: 小写 num 属性")
        assertTrue(legacy.contains("<DutyStatus>ALARM</DutyStatus>"))
        assertTrue(legacy.contains("<Item>"), "2016 也要带 Item 列表")
        assertFalse(legacy.contains("<AlarmStatus>"), "扁平 AlarmStatus 在两版都是非 schema 元素")
    }

    @Test
    fun `AlarmStatus 的有效版本是 2016 时回 NotNumber`() {
        val snapshot = AlarmStatusSnapshot(alarming = true, alarmChannelId = "34020000001340000001")
        val modern = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snapshot, gbVersion = GbVersion.V2022)
        val legacy = AlarmStatusResponse.build(cfg(), sn = "1", snapshot = snapshot, gbVersion = GbVersion.V2016)

        assertTrue(modern.contains("<DutyStatus>ALARM</DutyStatus>"))
        assertTrue(legacy.contains("<NotNumber>1</NotNumber>"))
        assertFalse(legacy.contains("<DutyStatus>"), "2016 形态不该出现 DutyStatus")
    }

    @Test
    fun `不传版本参数时保持既有行为 取本机声明版本`() {
        val modern = cfg(GbVersion.V2022)
        val legacy = cfg(GbVersion.V2016)
        assertTrue(DeviceInfoResponse.build(modern, sn = "1").contains("<HardwareVersion>"))
        assertFalse(DeviceInfoResponse.build(legacy, sn = "1").contains("<HardwareVersion>"))
    }
}
