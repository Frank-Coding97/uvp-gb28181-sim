package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 §9.13.1 + A.2.5.9 设备软件升级结果通知。
 *
 * ⭐ 2026-09-19 修正三处（原先三处全错，本测试是配套新增的）：
 *  1. 元素名 `Result` → **`UpgradeResult`**，取值域 `tg:resultType` = **OK / ERROR**
 *     （不是原先的 0/1/2）；
 *  2. **`Percent` 删除** —— 2022 全书 0 命中，标准里没有"升级进度百分比"这回事；
 *  3. **只在流程结束后发一条**（§9.13.1 a)「设备升级流程结束后……发送设备软件升级结果通知命令」）。
 *     第 3 点是控制流的事，见 `ManscdpRouterImpl.runUpgradeProgressFlow`；这里钉报文形态。
 */
class DeviceUpgradeResultNotifyTest {

    private val deviceId = "34020000001320000001"

    private fun xml(
        result: String = DeviceUpgradeResultNotify.RESULT_OK,
        firmware: String = "v1.2.3",
        sessionId: String = "abc-123",
        failedReason: String? = null,
    ) = DeviceUpgradeResultNotify.buildXml(
        deviceId = deviceId, sn = 17, sessionId = sessionId,
        firmware = firmware, upgradeResult = result, failedReason = failedReason
    )

    @Test
    fun cmdType_and_wrapper() {
        assertTrue(xml().contains("<CmdType>DeviceUpgradeResult</CmdType>"))
        assertTrue(xml().contains("<Notify>"))
        assertTrue(xml().contains("</Notify>"))
    }

    @Test
    fun success_emitsUpgradeResultOk_andNoFailedReason() {
        val x = xml()
        assertTrue(x.contains("<UpgradeResult>OK</UpgradeResult>"))
        assertTrue(x.contains("<Firmware>v1.2.3</Firmware>"))
        assertTrue(x.contains("<SessionID>abc-123</SessionID>"))
        assertTrue(x.contains("<SN>17</SN>"))
        assertTrue(x.contains("<DeviceID>$deviceId</DeviceID>"))
        assertFalse(x.contains("<UpgradeFailedReason>"), "成功时 `minOccurs=0`，补空元素反而多余")
    }

    @Test
    fun failure_emitsErrorAndReason() {
        val x = xml(result = DeviceUpgradeResultNotify.RESULT_ERROR, failedReason = "02")
        assertTrue(x.contains("<UpgradeResult>ERROR</UpgradeResult>"))
        assertTrue(x.contains("<UpgradeFailedReason>02</UpgradeFailedReason>"))
    }

    /** `tg:resultType` 只有 OK / ERROR 两档 —— 传别的值必须在构造前就被挡住。 */
    @Test
    fun invalidResult_isRejectedBeforeSending() {
        for (bad in listOf("0", "1", "2", "SUCCESS", "ok", "")) {
            assertFailsWith<IllegalArgumentException>("'$bad' 不该被放行") {
                xml(result = bad)
            }
        }
    }

    /** 失败原因取值域来自 A.2.5.9：01 下载超时 / 02 包损坏 / 03 系统异常 / 99 其他。 */
    @Test
    fun failedReasonConstValues_matchA2259() {
        assertEquals("01", DeviceUpgradeResultNotify.REASON_DOWNLOAD_TIMEOUT)
        assertEquals("02", DeviceUpgradeResultNotify.REASON_PACKAGE_CORRUPTED)
        assertEquals("03", DeviceUpgradeResultNotify.REASON_SYSTEM_ERROR)
        assertEquals("99", DeviceUpgradeResultNotify.REASON_OTHER)
    }

    /**
     * ⛔ `Percent` 是自造元素（2022 全书 0 命中）。
     * 设备屏幕上仍可以有进度条（那是本机 `UpgradeProgress` UI 状态），但**不上报平台**——
     * 平台侧没有接收它的字段，发了只会是一条谁都忽略的噪声，且让"标准里有没有这个流程"
     * 变得含糊。
     */
    @Test
    fun noPercentElement_everPresent() {
        assertFalse(xml().contains("Percent"), "标准里没有 Percent")
        assertFalse(xml(result = DeviceUpgradeResultNotify.RESULT_ERROR, failedReason = "03").contains("Percent"))
    }

    /** 升级包版本号与 SessionID 都来自平台报文，含 XML 元字符时要转义。 */
    @Test
    fun firmware_isXmlEscaped() {
        val x = xml(firmware = "v1.0<beta>&test")
        assertTrue(x.contains("<Firmware>v1.0&lt;beta&gt;&amp;test</Firmware>"))
        assertFalse(x.contains("v1.0<beta>"))
    }

    @Test
    fun failedReason_isXmlEscaped() {
        val x = xml(result = DeviceUpgradeResultNotify.RESULT_ERROR, failedReason = "0<1")
        assertTrue(x.contains("<UpgradeFailedReason>0&lt;1</UpgradeFailedReason>"))
    }

    /** 成功却带了 failedReason 时不发该元素（形态由 result 决定，不由调用方随手传的值决定）。 */
    @Test
    fun successWithFailedReason_stillOmitsTheElement() {
        val x = xml(failedReason = "03")
        assertFalse(x.contains("<UpgradeFailedReason>"), "result=OK 时不该有失败原因: $x")
    }
}
