package com.uvp.sim.sip

/**
 * GB/T 28181-2022 §9.13 + A.2.5.9 DeviceUpgradeResult Notify(设备升级结果上报).
 *
 * 平台下发 DeviceUpgrade(A.2.3.1.12)给设备 → 设备应每秒推一次进度 → 完成时推最后一次 status=1.
 *
 * 报文格式:
 * ```xml
 * <?xml version="1.0" encoding="GB2312"?>
 * <Notify>
 *   <CmdType>DeviceUpgradeResult</CmdType>
 *   <SN>17</SN>
 *   <DeviceID>34020000001320000001</DeviceID>
 *   <SessionID>abc-123</SessionID>     ← 透传平台下发的 SessionID
 *   <Firmware>v1.2.3</Firmware>
 *   <Result>0</Result>                  ← 0=进行中 / 1=成功 / 2=失败
 *   <Percent>30</Percent>               ← 进度百分比 0-100
 * </Notify>
 * ```
 *
 * UpgradeResult 取值(GB-2022 A.2.5.9 表):
 *   0 = 进行中 (in-progress)
 *   1 = 成功   (success)
 *   2 = 失败   (failure)
 */
object DeviceUpgradeResultNotify {

    const val RESULT_IN_PROGRESS = 0
    const val RESULT_SUCCESS = 1
    const val RESULT_FAILURE = 2

    fun buildXml(
        deviceId: String,
        sn: Int,
        sessionId: String,
        firmware: String,
        result: Int,
        percent: Int,
    ): String = """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>DeviceUpgradeResult</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<SessionID>$sessionId</SessionID>
<Firmware>$firmware</Firmware>
<Result>$result</Result>
<Percent>${percent.coerceIn(0, 100)}</Percent>
</Notify>
"""

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
        result: Int,
        percent: Int,
    ): SipRequest = SipBuilders.buildMessage(
        config = config,
        cseq = cseq,
        callId = callId,
        branch = branch,
        fromTag = fromTag,
        localIp = localIp,
        localPort = localPort,
        xmlBody = buildXml(config.device.deviceId, sn, sessionId, firmware, result, percent),
    )
}
