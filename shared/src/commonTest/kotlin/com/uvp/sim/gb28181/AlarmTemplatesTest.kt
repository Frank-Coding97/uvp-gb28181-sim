package com.uvp.sim.gb28181

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2 — AlarmTemplates 内置模板库 + 受控随机 + toPayload 填充。
 */
class AlarmTemplatesTest {

    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "test-password"
        ),
        mockPosition = GeoPoint(116.404, 39.915)
    )

    @Test
    fun `内置 7 条模板`() {
        assertEquals(7, AlarmTemplates.builtin.size)
    }

    /** 移动侦测 / 区域入侵各有一条，且只有这两条带门控标签 —— 其余属于通用报警。 */
    @Test
    fun `只有移动侦测与区域入侵带门控标签`() {
        val motion = AlarmTemplates.builtin.filter { it.reportKind == AlarmReportKind.MotionDetection }
        val field = AlarmTemplates.builtin.filter { it.reportKind == AlarmReportKind.FieldDetection }
        assertEquals(1, motion.size, "移动侦测模板应恰好一条")
        assertEquals(1, field.size, "区域入侵模板应恰好一条")
        assertEquals("motion", motion.single().typeParam)
        assertEquals("field", field.single().typeParam)
        assertEquals(
            5,
            AlarmTemplates.builtin.count { it.reportKind == null },
            "通用报警不应被 A.2.1.18 开关拦住"
        )
    }

    /** 门控标签要跟着 `toPayload` 一起走，否则上报路径永远看不到它。 */
    @Test
    fun `toPayload 透传门控标签`() {
        val tpl = AlarmTemplates.builtin.first { it.reportKind == AlarmReportKind.MotionDetection }
        val payload = tpl.toPayload(config)
        assertEquals(AlarmReportKind.MotionDetection, payload.reportKind)
        assertEquals("motion", payload.typeParam)
    }

    /** 随机池按开关过滤：两类都关时仍能抽到通用报警，不会返回空。 */
    @Test
    fun `random 排除被关闭的类别`() {
        repeat(20) {
            val t = AlarmTemplates.random(Random(it), allowed = { kind -> kind == null })
            assertEquals(null, t.reportKind)
        }
        val onlyField = AlarmTemplates.random(Random(1), allowed = { it == AlarmReportKind.FieldDetection })
        assertEquals(AlarmReportKind.FieldDetection, onlyField.reportKind)
    }

    @Test
    fun `每条模板字段非空`() {
        AlarmTemplates.builtin.forEach {
            assertTrue(it.description.isNotBlank(), "描述不能空")
        }
    }

    @Test
    fun `random 同 seed 同结果`() {
        val a = AlarmTemplates.random(Random(42))
        val b = AlarmTemplates.random(Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `random 返回的是内置模板之一`() {
        repeat(20) {
            val t = AlarmTemplates.random(Random(it))
            assertTrue(t in AlarmTemplates.builtin)
        }
    }

    @Test
    fun `toPayload 填 deviceId 和经纬度`() {
        val payload = AlarmTemplates.builtin.first().toPayload(config)
        assertEquals("34020000001340000001", payload.deviceId)
        assertEquals(116.404, payload.longitude)
        assertEquals(39.915, payload.latitude)
    }

    @Test
    fun `toPayload 保留模板语义字段`() {
        val tpl = AlarmTemplates.builtin.first { it.type == AlarmType.VideoLost }
        val payload = tpl.toPayload(config)
        assertEquals(AlarmType.VideoLost, payload.type)
        assertEquals(tpl.priority, payload.priority)
        assertEquals(tpl.method, payload.method)
        assertEquals(tpl.description, payload.description)
    }
}
