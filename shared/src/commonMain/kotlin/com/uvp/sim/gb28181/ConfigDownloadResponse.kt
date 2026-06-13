package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig

/**
 * GB/T 28181 §9.3.5 ConfigDownload 应答构造。
 *
 * 平台下发 MESSAGE body:
 * ```xml
 * <Query>
 *   <CmdType>ConfigDownload</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <ConfigType>BasicParam[/VideoParamOpt/...]</ConfigType>  ← 多个用 / 分隔
 * </Query>
 * ```
 *
 * 设备回 MESSAGE body:
 * ```xml
 * <Response>
 *   <CmdType>ConfigDownload</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Result>OK</Result>
 *   <BasicParam>
 *     <Name>...</Name>
 *     <DeviceID>...</DeviceID>
 *     <Expiration>3600</Expiration>
 *     <HeartBeatInterval>60</HeartBeatInterval>
 *     <HeartBeatCount>3</HeartBeatCount>
 *   </BasicParam>
 *   <VideoParamOpt>
 *     <DownloadSpeed>1/2/4</DownloadSpeed>
 *     <Resolution>...</Resolution>
 *   </VideoParamOpt>
 * </Response>
 * ```
 *
 * 实现策略:
 *   - 按 [parseConfigTypes] 解析 ConfigType(/ 分隔),只输出请求过的块
 *   - 不识别的类型(SVAC*)忽略,仍回 Result=OK
 *   - 字段值直接读 SimConfig,跟设备实际运行参数一致
 */
object ConfigDownloadResponse {

    /** 解析 ConfigType="A/B/C" 为集合,大小写归一为 PascalCase 原样 */
    fun parseConfigTypes(xml: String): List<String> {
        val raw = ManscdpParser.tagValue(xml, "ConfigType") ?: return emptyList()
        return raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun build(config: SimConfig, sn: String, configTypes: List<String>): String {
        val device = config.device
        val blocks = StringBuilder()
        if (configTypes.any { it.equals("BasicParam", ignoreCase = true) }) {
            blocks.append(buildBasicParam(config))
        }
        if (configTypes.any { it.equals("VideoParamOpt", ignoreCase = true) }) {
            blocks.append(buildVideoParamOpt(config))
        }
        // 其它 ConfigType(SVACEncodeConfig 等)模拟器不支持,忽略,仍回 OK 让平台不报错
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>ConfigDownload</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<Result>OK</Result>
${blocks}</Response>
""".replace("\n", "\r\n")
    }

    private fun buildBasicParam(config: SimConfig): String {
        val d = config.device
        return """<BasicParam>
<Name>${d.name}</Name>
<DeviceID>${d.deviceId}</DeviceID>
<Expiration>${config.expiresSeconds}</Expiration>
<HeartBeatInterval>${config.keepaliveIntervalSeconds}</HeartBeatInterval>
<HeartBeatCount>${config.maxKeepaliveTimeouts}</HeartBeatCount>
</BasicParam>
"""
    }

    private fun buildVideoParamOpt(config: SimConfig): String {
        val v = config.video
        return """<VideoParamOpt>
<DownloadSpeed>1/2/4</DownloadSpeed>
<Resolution>${v.resolution.label}</Resolution>
</VideoParamOpt>
"""
    }
}
