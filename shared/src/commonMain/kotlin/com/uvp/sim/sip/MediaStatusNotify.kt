package com.uvp.sim.sip

/**
 * GB/T 28181 §9.5.3 MediaStatus Notify(M3 §D 录像下载完成通知)。
 *
 * 报文格式(NotifyType=121 = 历史媒体文件发送结束):
 * ```xml
 * <?xml version="1.0" encoding="GB2312"?>
 * <Notify>
 *   <CmdType>MediaStatus</CmdType>
 *   <SN>17</SN>
 *   <DeviceID>34020000001320000001</DeviceID>
 *   <NotifyType>121</NotifyType>
 * </Notify>
 * ```
 *
 * SipBuilders.buildMessage 套外层 SIP MESSAGE,Content-Type: application/MANSCDP+xml。
 */
object MediaStatusNotify {

    /** 121 = 历史媒体文件发送结束(GB/T §9.5.3 表 22) */
    const val NOTIFY_TYPE_DOWNLOAD_END = 121

    fun buildXml(deviceId: String, sn: Int, notifyType: Int = NOTIFY_TYPE_DOWNLOAD_END): String =
        """<?xml version="1.0" encoding="GB2312"?>
<Notify>
<CmdType>MediaStatus</CmdType>
<SN>$sn</SN>
<DeviceID>$deviceId</DeviceID>
<NotifyType>$notifyType</NotifyType>
</Notify>
"""

    /**
     * 构造完整 SIP MESSAGE 报文(用于 SimulatorEngine 直接 transport.send)。
     *
     * @param config SIP 配置
     * @param cseq 设备 CSeq 计数
     * @param callId Notify 一般用新 callId(MESSAGE 是独立事务,与 Download 的 INVITE callId 无关)
     * @param branch 新 branch(每次新事务)
     * @param fromTag 新 fromTag
     * @param localIp 设备本地 IP
     * @param localPort 设备本地 SIP 端口
     * @param sn Notify 序号(单 device 全局递增)
     * @param notifyType 默认 121(下载完成)
     */
    fun build(
        config: com.uvp.sim.config.SimConfig,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int,
        sn: Int,
        notifyType: Int = NOTIFY_TYPE_DOWNLOAD_END
    ): SipRequest = SipBuilders.buildMessage(
        config = config,
        cseq = cseq,
        callId = callId,
        branch = branch,
        fromTag = fromTag,
        localIp = localIp,
        localPort = localPort,
        xmlBody = buildXml(config.device.deviceId, sn, notifyType)
    )
}
