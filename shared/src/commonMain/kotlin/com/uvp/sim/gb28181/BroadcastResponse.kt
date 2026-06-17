package com.uvp.sim.gb28181

/**
 * GB/T 28181 §9.8 / §F.2.1 语音广播业务应答(Broadcast Response)。
 *
 * 平台下发 `MESSAGE` 携带 `<CmdType>Broadcast</CmdType>` 通知设备准备接收语音广播,
 * 设备先回 SIP 200 OK,再以一条独立 MESSAGE 回 MANSCDP Broadcast Response 表明
 * 是否接受(Result=OK)或拒绝(Result=ERROR + Reason)。
 *
 * 字段顺序钉死 CmdType < SN < DeviceID < Result(< Reason),GB2312 声明与平台一致。
 */
object BroadcastResponse {

    enum class Result { OK, ERROR }

    fun build(deviceId: String, sn: String, result: Result, reason: String? = null): String =
        buildString {
            append("""<?xml version="1.0" encoding="GB2312"?>""")
            append("\r\n<Response>")
            append("\r\n<CmdType>Broadcast</CmdType>")
            append("\r\n<SN>").append(sn).append("</SN>")
            append("\r\n<DeviceID>").append(deviceId).append("</DeviceID>")
            append("\r\n<Result>").append(result.name).append("</Result>")
            if (reason != null) append("\r\n<Reason>").append(reason).append("</Reason>")
            append("\r\n</Response>\r\n")
        }
}
