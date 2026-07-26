package com.uvp.sim.ui

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.QrFetchResult
import com.uvp.sim.config.QrProvisionPayload
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.ui.model.SipStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T9 纯逻辑单测 —— 扫码入口拦截 / 确认页数据组装 / 错误文案映射 / 回填。
 *
 * 硬件路径(CameraX / AVFoundation)按项目惯例以真机兜底,这里只覆盖能在
 * commonTest 跑的判定逻辑,那也正是评审关心的几条(入口态、host 信任、竞态)。
 */
class QrProvisionUiTest {

    // ---- 入口态拦截(用例 9.1-9.5) ----

    @Test
    fun disconnected_is_the_only_state_that_may_scan() {
        assertNull(qrEntryBlockReason(SipStateDto.Disconnected))
    }

    @Test
    fun registering_is_blocked() {
        assertNotNull(qrEntryBlockReason(SipStateDto.Registering))
    }

    @Test
    fun registered_is_blocked() {
        assertNotNull(qrEntryBlockReason(SipStateDto.Registered))
    }

    @Test
    fun in_call_is_blocked() {
        assertNotNull(qrEntryBlockReason(SipStateDto.InCall))
    }

    /**
     * Failed 也必须拦 —— AppEngine.updateConfig 在 engine != null 时会
     * disconnect(); connect(),而 Failed 态 engine 可能仍存活(plan §5.2)。
     */
    @Test
    fun failed_is_blocked_too() {
        assertNotNull(qrEntryBlockReason(SipStateDto.Failed))
    }

    @Test
    fun every_blocked_state_shares_the_same_guidance_text() {
        val texts = listOf(
            SipStateDto.Registering,
            SipStateDto.Registered,
            SipStateDto.InCall,
            SipStateDto.Failed,
        ).map { qrEntryBlockReason(it) }
        assertEquals(1, texts.distinct().size)
        assertEquals("请先断开连接再扫码配置", texts.first())
    }

    // ---- 错误文案映射(plan §5.9,用例 9.12-9.15) ----

    @Test
    fun invalidated_maps_to_platform_regenerate_hint() {
        val message = qrErrorMessage(QrFetchResult.Invalidated("二维码已失效,请在平台重新生成"))
        assertEquals("二维码已失效,请在平台重新生成", message)
    }

    @Test
    fun invalidated_without_server_message_falls_back_to_standard_text() {
        assertEquals(
            "二维码已失效,请在平台重新生成",
            qrErrorMessage(QrFetchResult.Invalidated("")),
        )
    }

    @Test
    fun malformed_maps_to_corrupted_text() {
        assertEquals("二维码内容已损坏", qrErrorMessage(QrFetchResult.Malformed("")))
    }

    @Test
    fun server_error_points_at_platform_logs_not_user_network() {
        val message = qrErrorMessage(QrFetchResult.ServerError(500))
        assertEquals("平台处理失败,请查看平台日志", message)
    }

    @Test
    fun network_error_points_at_same_network_check() {
        assertEquals(
            "连不上平台,请检查手机与平台是否同网络",
            qrErrorMessage(QrFetchResult.NetworkError("connect timed out")),
        )
    }

    @Test
    fun unrecognised_qr_has_its_own_text() {
        assertEquals("不是 UVP 平台的接入二维码", QR_NOT_UVP_CODE)
    }

    // ---- 确认页数据组装(用例 9.6,评审 HIGH host 信任边界) ----

    @Test
    fun confirm_summary_surfaces_the_target_host() {
        val summary = buildQrConfirmSummary("http://192.168.1.10:8280", samplePayload())
        assertEquals("http://192.168.1.10:8280", summary.host)
    }

    @Test
    fun confirm_summary_carries_the_six_tuple_digest() {
        val summary = buildQrConfirmSummary("http://a:8280", samplePayload())
        assertEquals("34020000002000000001", summary.serverId)
        assertEquals("3402000000", summary.domain)
        assertEquals("192.168.1.20:5060", summary.endpoint)
        assertEquals("UDP", summary.transport)
    }

    /** 密码只显示位数,不显示内容(spec 要求 + 防截图外泄)。 */
    @Test
    fun confirm_summary_masks_password_to_digit_count_only() {
        val summary = buildQrConfirmSummary("http://a:8280", samplePayload(password = "s3cr3t!!"))
        assertEquals(8, summary.passwordLength)
        assertTrue(summary.passwordHint.none { it in 's'..'t' })
        assertEquals("已设置(8 位)", summary.passwordHint)
    }

    @Test
    fun confirm_summary_flags_plain_http_to_public_host_as_risky() {
        val lan = buildQrConfirmSummary("http://192.168.1.10:8280", samplePayload())
        val public = buildQrConfirmSummary("http://203.0.113.9:8280", samplePayload())
        val tls = buildQrConfirmSummary("https://203.0.113.9", samplePayload())
        assertTrue(!lan.insecurePublicHost)
        assertTrue(public.insecurePublicHost)
        assertTrue(!tls.insecurePublicHost)
    }

    // ---- 回填(用例 9.8,plan §5.7) ----

    @Test
    fun apply_payload_fills_five_fields() {
        val updated = applyQrPayload(baseConfig(), samplePayload())
        assertEquals("192.168.1.20", updated.server.ip)
        assertEquals(5060, updated.server.port)
        assertEquals("34020000002000000001", updated.server.serverId)
        assertEquals("3402000000", updated.server.domain)
        assertEquals("gb-secret", updated.device.password)
    }

    /** deviceId 不动(spec §2.1)—— 设备身份是本机的,不该被平台二维码改写。 */
    @Test
    fun apply_payload_never_touches_device_id() {
        val base = baseConfig()
        val updated = applyQrPayload(base, samplePayload())
        assertEquals(base.device.deviceId, updated.device.deviceId)
        assertEquals(base.device.videoChannelId, updated.device.videoChannelId)
    }

    @Test
    fun apply_payload_maps_transport_case_insensitively() {
        val updated = applyQrPayload(baseConfig(), samplePayload(transport = "TCP"))
        assertEquals(TransportType.TCP, updated.transport)
    }

    /** transport 解不出时保持原值,不静默改协议(QrPayloadValidator 已先拦住)。 */
    @Test
    fun apply_payload_keeps_existing_transport_when_unparsable() {
        val base = baseConfig().copy(transport = TransportType.TCP)
        val updated = applyQrPayload(base, samplePayload(transport = "sctp"))
        assertEquals(TransportType.TCP, updated.transport)
    }

    @Test
    fun apply_payload_leaves_the_rest_of_config_untouched() {
        val base = baseConfig()
        val updated = applyQrPayload(base, samplePayload())
        assertEquals(base.copy(server = updated.server, device = updated.device), updated)
    }

    @Test
    fun filled_config_becomes_ready_to_register() {
        assertTrue(!baseConfig().isReadyToRegister)
        assertTrue(applyQrPayload(baseConfig(), samplePayload()).isReadyToRegister)
    }

    // ---- helpers ----

    private fun samplePayload(
        transport: String = "udp",
        password: String = "gb-secret",
    ) = QrProvisionPayload(
        serverId = "34020000002000000001",
        domain = "3402000000",
        ip = "192.168.1.20",
        port = 5060,
        transport = transport,
        password = password,
    )

    private fun baseConfig() = SimConfig(
        server = ServerConfig(ip = "", port = 0, serverId = "", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001320000010",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "",
        ),
        transport = TransportType.UDP,
    )
}
