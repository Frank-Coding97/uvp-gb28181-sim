package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoResolution

/**
 * GB/T 28181 §9.3.5 / A.2.4.7 → A.2.6.9 ConfigDownload 应答构造。
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
 * 设备回 MESSAGE body（元素顺序照 **A.2.6.9** 的消息体定义）:
 * ```xml
 * <Response>
 *   <CmdType>ConfigDownload</CmdType>
 *   <SN>...</SN>
 *   <DeviceID>...</DeviceID>
 *   <Result>OK</Result>
 *   <BasicParam>…</BasicParam>
 *   <VideoParamOpt>…</VideoParamOpt>
 *   <VideoParamAttribute Num="…">…</VideoParamAttribute>
 *   <VideoRecordPlan>…</VideoRecordPlan>
 *   <VideoAlarmRecord>…</VideoAlarmRecord>
 *   <PictureMask>…</PictureMask>
 *   <FrameMirror>…</FrameMirror>
 *   <AlarmReport>…</AlarmReport>
 *   <OSDConfig>…</OSDConfig>
 *   <SnapShot>…</SnapShot>      ← ⛔ 回读元素名是 SnapShot，不是 SnapShotConfig
 * </Response>
 * ```
 *
 * ## 三条口径（都是刻意选的，不是顺手这么写）
 *
 * **① 只回平台点名要的、且设备真的有的块。**
 * 请求里点名了、但模拟器没实现（`SVACEncodeConfig` / `SVACDecodeConfig`）⇒ **不回该块，但仍回
 * `Result=OK`**。这是真实设备的行为：`ConfigType` 的元素全是 `minOccurs="0"`，
 * 设备不支持的配置类型就是"没有这一块"，平台据此判 `type_absent`（设备不支持该类型）是
 * **正确结论**，不是假阴性 —— 假阴性说的是"设备明明实现了却回空"。
 * ⛔ 别改成回 `Result=ERROR`：那会把"设备不支持这一项"报成"这次查询失败了"，
 * 让平台把整条查询记为错误并重试，两个语义完全不同。
 * ⭐ 为了现场能回答"到底是设备没实现，还是版本档位问题"，应答日志必须把
 * **未实现清单**和**版本门禁挡掉的清单**分开打（见 `CatalogSubRouter.configDownloadNote`）。
 *
 * **② 2022 新增类型按**有效**国标版本门禁。**
 * `VideoParamAttribute` / `VideoRecordPlan` / … 这批 2022 才引入的类型，只有在
 * `effectiveGbVersion == V2022`（附录 I 协商结果，= `min(本机声明, 平台声明)`）时才回。
 * 有效版本是 2016 时整块不出现、仍回 OK —— 这正是真实 2016 设备的形态。
 * ⛔ **门禁只关"设备主动声明自己支持什么"这一半**；平台**下发**那一半绝不按版本拦
 * （被误登记成 2016 的真 2022 设备必须还有一次"试一下"的机会，见
 * `DeviceControlDispatcher` 里那些没有任何版本判断的分支）。
 *
 * **③ 回读永远有值**（除了抓拍配置这一处例外）。
 * 平台从未配过的类型回**出厂默认**，不回空 —— 回空会被判 `type_absent`，
 * 把"支持但没人配过"误报成"设备不支持"。抓拍配置例外：它的 `UploadURL` /
 * `SessionID` 是平台按会话给的，设备侧没有出厂默认可言，从未配过就**诚实地不回**
 * （见 `SnapShotReport`）。
 *
 * ⚠️ 标准允许"可同时查询多个配置类型，**可返回与查询 SN 值相同的多个响应，每个响应对应一个
 * 配置类型**"（A.2.4.7 正文）。本仓选择**合并为一条 Response**：A.2.6.9 的消息体本身就允许
 * 这些块作为兄弟元素同时出现（逐个核对过 XSD），而平台侧解析按单条处理。
 * 若将来要对齐"一类型一响应"的形态，改这一处即可。
 */
object ConfigDownloadResponse {

    /** 解析 ConfigType="A/B/C" 为集合,大小写归一为 PascalCase 原样 */
    fun parseConfigTypes(xml: String): List<String> {
        val raw = ManscdpParser.tagValue(xml, "ConfigType") ?: return emptyList()
        return raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 平台是否点名要了某个类型。
     *
     * ⭐ 抽出来是为了让「应答构造」与「应答日志」走**同一个判断** ——
     * 两处各写一遍大小写/集合比较,迟早分家,而分家后日志会撒谎
     * (日志说"没请求",实际回了/没回),这类日志比不写更有害。
     */
    fun requested(configTypes: List<String>, block: DeviceConfigBlock): Boolean =
        configTypes.any { it.equals(block.configType, ignoreCase = true) }

    /** 平台是否点名要了 `VideoParamAttribute`（保留旧签名，行为走 [requested]）。 */
    fun requestedVideoParamAttribute(configTypes: List<String>): Boolean =
        requested(configTypes, DeviceConfigBlock.VideoParamAttribute)

    /**
     * 本次应答会不会**真的**带上 `VideoParamAttribute` 块
     * = 平台点名要了 **且** 有效版本是 2022（见类注释 ②）。
     */
    fun emitsVideoParamAttribute(configTypes: List<String>, gbVersion: GbVersion): Boolean =
        emits(DeviceConfigBlock.VideoParamAttribute, configTypes, gbVersion)

    /** 平台点名要了、但**模拟器没实现**的类型（`SVAC*`）—— 只用于日志与自检，不影响应答内容。 */
    fun requestedButNotImplemented(configTypes: List<String>): List<String> =
        configTypes.filter { type ->
            DeviceConfigBlock.NOT_IMPLEMENTED.any { it.equals(type, ignoreCase = true) }
        }

    /**
     * 平台点名要了、但被**有效版本门禁**挡掉的类型（如有效版本 2016 时的 `PictureMask`）。
     *
     * ⛔ 这一项存在的唯一理由是现场可观测性：`type_absent` 在报文上与"设备不支持"完全同形，
     * 分不清就只能靠日志 —— 而这两种原因的处置方式相反（一个是换设备，一个是切档位重注册）。
     */
    fun requestedButVersionGated(configTypes: List<String>, gbVersion: GbVersion): List<String> =
        configTypes.filter { type ->
            DeviceConfigBlock.byConfigType(type)?.let { block -> gbVersion < block.since } == true
        }

    /**
     * 构造 A.2.6.9 的读取应答。
     *
     * @param requestedDeviceId **查询请求里的 `<DeviceID>`，原样回填**。
     *   ⛔ 绝不能用 `config.device.deviceId` 顶替：平台面板是**按通道**查的
     *   （`channel_code` = 34020000001320000010），而设备编码是另一个值；
     *   平台侧 `ptz/video_param.go` 用
     *   `ConfigDownloadExpectation{DeviceID: operationTargetCode(operation)}` 对账，
     *   回错编码的后果是**整条应答被判为不属于本次操作**，
     *   平台永远停在 `never_read`，而且**两侧日志都不报错**（实测 2026-09-18）。
     *   ⭐ 口径与 [com.uvp.sim.domain.coord.manscdp.CatalogSubRouter] 的 Catalog 分支一致：
     *   `ManscdpParser.deviceId(xml) ?: 设备 ID`。
     *   ⭐ 刻意**不给默认值**：漏传就等于回一个编码错但不报错的假应答。
     * @param gbVersion **有效**国标版本(附录 I 协商结果 = `min(本机, 平台)`),
     *   不是 `config.gbVersion`(那是本机声明)。⭐ 刻意**不给默认值**:给默认值会把
     *   「忘传 → 悄悄拿本机档兜底」变成一次静默的错误形态应答,而"对面是 2016"这一路
     *   恰好只在联调时出现。
     * @param videoParamOverrides 平台写入过的码流配置(`DeviceControlModel.videoParams`)。
     *   缺席的码流退回**出厂默认**,所以回读**永远有值** —— 见 [VideoParamAttribute.defaultFor]。
     * @param deviceConfigs 平台写入过的其余配置族(`DeviceControlModel.deviceConfigs`)。
     *   子项缺席一律退回该类型的出厂默认（抓拍配置除外，见 [SnapShotReport]）。
     */
    fun build(
        config: SimConfig,
        sn: String,
        requestedDeviceId: String,
        configTypes: List<String>,
        gbVersion: GbVersion,
        videoParamOverrides: Map<Int, VideoParamState> = emptyMap(),
        deviceConfigs: DeviceConfigState = DeviceConfigState(),
    ): String {
        val blocks = StringBuilder()
        // ⛔ 块顺序照 A.2.6.9 的消息体定义，不按"想到哪写到哪"排 ——
        //    顺序错不会报错，但"照标准逐行核对"这件事就失效了。
        if (emits(DeviceConfigBlock.BasicParam, configTypes, gbVersion)) {
            blocks.append(
                BasicParamConfig.render(
                    effective = BasicParamConfig.effective(config, deviceConfigs.basicParam),
                    // ⛔ 用**有效**版本：2016 的回读要比 2022 多报
                    //    PositionCapability / Longitude / Latitude（A.2.6 j) vs A.2.1.19）。
                    gbVersion = gbVersion,
                    // 经纬度取设备的模拟起点 —— 见 BasicParamConfig.render 的口径边界说明。
                    position = config.mockPosition,
                )
            )
        }
        if (emits(DeviceConfigBlock.VideoParamOpt, configTypes, gbVersion)) {
            blocks.append(buildVideoParamOpt())
        }
        if (emitsVideoParamAttribute(configTypes, gbVersion)) {
            blocks.append(
                VideoParamAttribute.renderBlock(
                    VideoParamAttribute.effectiveParams(config, videoParamOverrides)
                )
            )
        }
        if (emits(DeviceConfigBlock.VideoRecordPlan, configTypes, gbVersion)) {
            blocks.append(VideoRecordPlanConfig.render(deviceConfigs.effectiveVideoRecordPlan(config)))
        }
        if (emits(DeviceConfigBlock.VideoAlarmRecord, configTypes, gbVersion)) {
            blocks.append(VideoAlarmRecordConfig.render(deviceConfigs.effectiveVideoAlarmRecord(config)))
        }
        if (emits(DeviceConfigBlock.PictureMask, configTypes, gbVersion)) {
            blocks.append(PictureMaskConfig.render(deviceConfigs.effectivePictureMask(config)))
        }
        if (emits(DeviceConfigBlock.FrameMirror, configTypes, gbVersion)) {
            blocks.append(FrameMirrorConfig.render(deviceConfigs.effectiveFrameMirror()))
        }
        if (emits(DeviceConfigBlock.AlarmReport, configTypes, gbVersion)) {
            blocks.append(AlarmReportConfig.render(deviceConfigs.effectiveAlarmReport()))
        }
        if (emits(DeviceConfigBlock.OsdConfig, configTypes, gbVersion)) {
            blocks.append(FrontOsdConfig.render(deviceConfigs.effectiveFrontOsd(config)))
        }
        // 抓拍配置：⛔ **只有平台真下发过才回**（见 SnapShotReport 的类注释与类注释 ③）。
        if (emits(DeviceConfigBlock.SnapShot, configTypes, gbVersion)) {
            deviceConfigs.snapShot?.let { blocks.append(SnapShotReport.render(it)) }
        }
        // 其余类型（SVACEncodeConfig / SVACDecodeConfig）模拟器没实现 ⇒ 不回块、仍回 OK。
        // 理由见类注释 ①，未实现清单在应答日志里单独打（CatalogSubRouter.configDownloadNote）。
        return """<?xml version="1.0" encoding="GB2312"?>
<Response>
<CmdType>ConfigDownload</CmdType>
<SN>$sn</SN>
<DeviceID>$requestedDeviceId</DeviceID>
<Result>OK</Result>
${blocks}</Response>
""".replace("\n", "\r\n")
    }

    /**
     * A.2.1.20 `videoParamOptCfgType` —— 视频参数**范围**配置。
     *
     * `Resolution` 的语义是「摄像机**支持**的分辨率，可有多个值，各值间以 `/` 分隔」，
     * 所以这里报**档位全集**（[VideoResolution.entries] 的码值），而不是当前生效的那一个。
     * 当前生效值属于 `VideoParamAttribute`（A-5）的范畴，两者别混。
     *
     * ⛔ **必须发附录 G 的码值，不能发 [VideoResolution.label]（`"1280×720"`）。**
     * 标准要求 `1=QCIF / 2=CIF / 3=4CIF / 4=D1 / 5=720P / 6=1080P`，其余用 `WxH`。
     * 这里走 [VideoResolution.gb28181Code]（单一真源），与 SDP 协商
     * （[com.uvp.sim.sip.SipHeaderHelpers.buildSdpMediaSpec]）用的是同一份映射 ——
     * 曾经两处各写一份，导致 SDP 发码值、配置回读发人读串，同一台设备两条出口不一致。
     *
     * ⚠️ 口径边界：`VideoResolution` 是**模拟器允许用户选择的档位集**，
     * 不等于手机摄像头硬件真实支持的能力（CameraX 的 `setTargetResolution` 只是"请求"，
     * 实际以 `SurfaceRequest.resolution` 回调为准，见 `PlatformVideoCapabilities`）。
     * 真 IPC 上应报摄像头实际能力。
     */
    private fun buildVideoParamOpt(): String {
        val supported = VideoResolution.entries.joinToString("/") { it.gb28181Code.toString() }
        return """<VideoParamOpt>
<DownloadSpeed>1/2/4</DownloadSpeed>
<Resolution>$supported</Resolution>
</VideoParamOpt>
"""
    }
}
