package com.uvp.sim.gb28181

/**
 * GB/T 28181-2022 §9.5 图像抓拍完成后,设备主动发的 SIP MESSAGE NOTIFY body。
 *
 * 标准里 NOTIFY 信封形式有两种:
 *   - 主用:`<CmdType>Notify</CmdType>` + `<SubCmd>SnapShot</SubCmd>`(GB-2022 通用主动业务 envelope)
 *   - 兼容:`<CmdType>SnapShot</CmdType>`(部分平台只看 CmdType)
 *
 * [build] 走主用风格;[buildLegacy] 留 fallback,联调遇到平台不识别 SubCmd 时切换。
 */
object SnapShotNotifyBuilder {

    fun build(
        deviceId: String,
        sn: String,
        sessionId: String,
        snapShotId: String,
        timeIso: String,
        storagePath: String
    ): String = render(
        deviceId = deviceId,
        sn = sn,
        sessionId = sessionId,
        snapShotId = snapShotId,
        timeIso = timeIso,
        storagePath = storagePath,
        cmdType = "Notify",
        includeSubCmd = true
    )

    fun buildLegacy(
        deviceId: String,
        sn: String,
        sessionId: String,
        snapShotId: String,
        timeIso: String,
        storagePath: String
    ): String = render(
        deviceId = deviceId,
        sn = sn,
        sessionId = sessionId,
        snapShotId = snapShotId,
        timeIso = timeIso,
        storagePath = storagePath,
        cmdType = "SnapShot",
        includeSubCmd = false
    )

    private fun render(
        deviceId: String,
        sn: String,
        sessionId: String,
        snapShotId: String,
        timeIso: String,
        storagePath: String,
        cmdType: String,
        includeSubCmd: Boolean
    ): String {
        val subCmdLine = if (includeSubCmd) "<SubCmd>SnapShot</SubCmd>\n" else ""
        return ("""<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>$cmdType</CmdType>
$subCmdLine<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<SessionID>$sessionId</SessionID>
<SnapShotID>$snapShotId</SnapShotID>
<Time>$timeIso</Time>
<StoragePath>$storagePath</StoragePath>
</Notify>
""").replace("\n", "\r\n")
    }
}
