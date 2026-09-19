package com.uvp.sim.sip

import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.gb28181.escapeXmlText

/**
 * GB/T 28181-2022 §9.13.1 + A.2.5.9 设备软件升级结果通知。
 *
 * 标准报文形态:
 * ```xml
 * <Notify>
 *   <CmdType>DeviceUpgradeResult</CmdType>
 *   <SN>17</SN>
 *   <DeviceID>34020000001320000001</DeviceID>
 *   <SessionID>abc-123</SessionID>       <!-- 与 A.2.3.1.12 升级请求中的 SessionID 相同 -->
 *   <UpgradeResult>OK</UpgradeResult>    <!-- tg:resultType: OK | ERROR -->
 *   <Firmware>v1.2.3</Firmware>          <!-- 当前软件版本（必选） -->
 *   <UpgradeFailedReason>02</UpgradeFailedReason>  <!-- 失败时必选 -->
 * </Notify>
 * ```
 *
 * ⛔ **2026-09-19 三处按标准改正**（原先三处全错）:
 *  1. 元素名 `Result` → **`UpgradeResult`**，取值域是 `tg:resultType` = **`OK` / `ERROR`**，
 *     不是原先的 `0 / 1 / 2`。
 *  2. **`Percent` 被删除** —— 它在 2022 全书 **0 命中**。标准里**没有"升级进度百分比"**
 *     这回事，原先 KDoc 里那张"0=进行中/1=成功/2=失败 + 每秒推一次进度"的表是**编的**。
 *     ⭐ 设备屏幕上仍可以有进度条（[com.uvp.sim.domain.UpgradeProgress] 是本机 UI 状态），
 *     但**不上报给平台**：平台侧没有接收它的字段。
 *  3. **只在升级流程结束后发一条**。§9.13.1 a)「设备升级流程结束后，目标设备发送设备软件
 *     升级结果通知命令」、§9.13.2 第 12 步同义 —— 没有"逐秒推 4 条进度"的流程。
 *     控制流的改动见 `ManscdpRouterImpl.runUpgradeProgressFlow`。
 *
 * 失败原因取值（A.2.5.9，`minOccurs=0`，失败时必选）:
 * `01` 软件下载超时 / `02` 升级包损坏 / `03` 系统异常 / `99` 其他。
 */
object DeviceUpgradeResultNotify {

    /** `tg:resultType` 的取值域只有这两个 —— 没有"进行中"这一档。 */
    const val RESULT_OK = "OK"
    const val RESULT_ERROR = "ERROR"

    /** `UpgradeFailedReason` 的标准取值。 */
    const val REASON_DOWNLOAD_TIMEOUT = "01"
    const val REASON_PACKAGE_CORRUPTED = "02"
    const val REASON_SYSTEM_ERROR = "03"
    const val REASON_OTHER = "99"

    /**
     * @param upgradeResult 必须是 [RESULT_OK] / [RESULT_ERROR]。传别的值会被**原样发出去**，
     *   严格的对端按 `tg:resultType` 校验会判整条非法 —— 所以这里用 `require` 挡在构造前，
     *   而不是等到线上才发现。
     * @param failedReason [RESULT_ERROR] 时必选；成功时传 `null` 即**不发该元素**
     *   （标准 `minOccurs=0`，成功时补一个空元素反而是多余的）。
     */
    fun buildXml(
        deviceId: String,
        sn: Int,
        sessionId: String,
        firmware: String,
        upgradeResult: String,
        failedReason: String? = null,
    ): String {
        require(upgradeResult == RESULT_OK || upgradeResult == RESULT_ERROR) {
            "UpgradeResult 只能取 OK/ERROR（tg:resultType），收到 '$upgradeResult'"
        }
        val reasonBlock = if (upgradeResult == RESULT_ERROR && failedReason != null) {
            "<UpgradeFailedReason>${escapeXmlText(failedReason)}</UpgradeFailedReason>\n"
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>DeviceUpgradeResult</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<SessionID>$sessionId</SessionID>
<Firmware>${escapeXmlText(firmware)}</Firmware>
<UpgradeResult>$upgradeResult</UpgradeResult>
$reasonBlock</Notify>
"""
    }

    fun build(
        config: com.uvp.sim.config.SimConfig,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int,
        sn: Int,
        sessionId: String,
        firmware: String,
        upgradeResult: String,
        failedReason: String? = null,
        charset: SignalingCharset,
    ): SipRequest = SipBuilders.buildMessage(
        config = config,
        cseq = cseq,
        callId = callId,
        branch = branch,
        fromTag = fromTag,
        localIp = localIp,
        localPort = localPort,
        xmlBody = buildXml(config.device.deviceId, sn, sessionId, firmware, upgradeResult, failedReason),
        charset = charset,
    )
}
