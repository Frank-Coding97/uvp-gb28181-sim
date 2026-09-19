package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.gb28181.AlarmReportConfig
import com.uvp.sim.gb28181.BasicParamConfig
import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.VideoParamAttribute
import com.uvp.sim.gb28181.VideoParamState
import com.uvp.sim.ui.model.DeviceConfigRowDto
import com.uvp.sim.ui.model.DeviceConfigDto
import com.uvp.sim.ui.model.MaskRectDto
import com.uvp.sim.ui.model.OsdTextLineDto
import com.uvp.sim.ui.model.PictureMaskViewDto

/**
 * 「设备配置族」（GB/T 28181-2022 A.2.3.2）→ UI 视图态。
 *
 * 两条**必须**守住的规矩：
 *
 * 1. **归一化只在这里做一次**。协议侧的坐标是视频帧的绝对像素（遮挡 `Point`、
 *    OSD `X/Y`），而画布尺寸是运行期才知道的。这里按 `config.video.resolution` 除一次，
 *    之后渲染层只管乘画布尺寸 —— 两边各换算一次就会静默错位（错位还很难发现，
 *    因为矩形仍然"画出来了"）。
 *
 * 2. **只映射"平台真的下发过"的那部分**（`deviceConfigs.xxx != null`），不回退出厂默认。
 *    理由见 [DeviceConfigDto] 的类注释：出厂默认的 OSD 在画布上已经由本机三层 OSD 画过，
 *    回退出来就是同一行字重合两遍。摘要行那边相反 —— 那里**要有**出厂默认，
 *    因为操作员需要看到"这项设备现在是什么状态"，并在 `fromPlatform=false` 时知道
 *    那不是平台配的。
 */
fun DeviceControlModel.toDeviceConfigDto(config: SimConfig): DeviceConfigDto {
    val frameWidth = config.video.resolution.widthPx.toFloat().coerceAtLeast(1f)
    val frameHeight = config.video.resolution.heightPx.toFloat().coerceAtLeast(1f)

    // ---- 画布叠层：只取平台下发过的 ----
    val mask = deviceConfigs.pictureMask
    val maskView = if (mask == null) {
        PictureMaskViewDto()
    } else {
        PictureMaskViewDto(
            on = mask.on == 1,
            rects = mask.regions.map { region ->
                MaskRectDto(
                    left = (region.left / frameWidth).coerceIn(0f, 1f),
                    top = (region.top / frameHeight).coerceIn(0f, 1f),
                    right = (region.right / frameWidth).coerceIn(0f, 1f),
                    bottom = (region.bottom / frameHeight).coerceIn(0f, 1f),
                )
            },
        )
    }
    val osd = deviceConfigs.frontOsd
    // OSD 的画布坐标也要按**它自己声明的窗口尺寸**归一化，不能用 config 的分辨率 ——
    // 标准里 Length/Width 就是"这块 OSD 的坐标系有多大"，平台按自己那份填的。
    // 用 config 的分辨率归一化会在平台填了别的窗口尺寸时整体偏移。
    val osdWidth = (osd?.length ?: 0).toFloat().takeIf { it > 0f } ?: frameWidth
    val osdHeight = (osd?.width ?: 0).toFloat().takeIf { it > 0f } ?: frameHeight
    val osdLines = osd?.items.orEmpty().map { item ->
        OsdTextLineDto(
            text = item.text,
            x = (item.x / osdWidth).coerceIn(0f, 1f),
            y = (item.y / osdHeight).coerceIn(0f, 1f),
        )
    }

    return DeviceConfigDto(
        pictureMask = maskView,
        osdLines = osdLines,
        // ⛔ 翻转只在平台下发过时生效：`frameMirror` 为 null（没配过）时保持 0，
        //    不能用 FrameMirrorConfig.DEFAULT（也是 0，但要靠"没配就不动"这个显式判断表达意图）。
        frameMirror = deviceConfigs.frameMirror?.value ?: 0,
        // 原样透出（不做归一化）：设置页要拿它回写成协议字段。
        frontOsd = osd,
        rows = deviceConfigs.toSummaryRows(config, videoParams),
    )
}

/**
 * HUD「图像」页的只读摘要行。
 *
 * ⭐ 行**恒定 9 条、顺序固定**（视频参数属性 / 遮挡 / 翻转 / 前端 OSD / 录像计划 / 报警录像 /
 * 报警上报 / 基本参数 / 图像抓拍），缺席的显示"出厂默认"-类文案而不是整行消失 ——
 * 固定行数让操作员扫一眼就知道"这一族一共有哪些项"，
 * 而"行数随配置变化"会让面板每次下发后重排，反而看不出改了什么。
 * （与"集合条数由协议决定时必须限量+报余数"的那条约定不冲突：这里 9 是**类型数**，不是数据条数。）
 *
 * ⚠️ 9 = 2022 附录 A 设备配置族的 12 个 `ConfigType` − SVAC 编/解码 2 项（本仓明确不做）
 * − `VideoParamOpt`（它是设备**能力范围**、由回读应答派生，不是"一项待配配置"）。
 * 口径别随手改：数出来的项数要和 `DeviceConfigBlock` 枚举对得上。
 *
 * ⭐ 其中「视频参数属性」是**常驻行**（`alwaysVisible`）且排第一，理由见
 * [com.uvp.sim.ui.model.DeviceConfigRowDto.alwaysVisible]：它永远有生效值，
 * 不能跟着 `fromPlatform` 被过滤掉。
 */
private fun DeviceConfigState.toSummaryRows(
    config: SimConfig,
    videoParams: Map<Int, VideoParamState>,
): List<DeviceConfigRowDto> {
    fun r(
        label: String,
        fromPlatform: Boolean,
        value: String,
        alwaysVisible: Boolean = false,
    ) = DeviceConfigRowDto(
        label = label,
        value = value,
        fromPlatform = fromPlatform,
        alwaysVisible = alwaysVisible,
    )

    return listOf(
        // ⭐ 常驻行（`alwaysVisible`），且**排第一** —— 它是这一族里唯一"永远有值"的项。
        // 详见 [DeviceConfigRowDto.alwaysVisible]；没它的话"设备现在主/子码流各是什么"
        // 在设备屏幕上无处可见。
        r(
            "视频参数属性", videoParams.isNotEmpty(),
            videoParamSummary(config, videoParams),
            alwaysVisible = true,
        ),
        r(
            "画面遮挡", pictureMask != null,
            pictureMask?.let { if (it.on == 1) "开启 · ${it.sumNum} 区" else "关闭" } ?: "关闭（出厂默认）",
        ),
        r(
            "画面翻转", frameMirror != null,
            frameMirror?.displayLabel ?: "不启用（出厂默认）",
        ),
        r(
            "前端 OSD", frontOsd != null,
            frontOsd?.let {
                "时间${if (it.timeEnable == 1) "开" else "关"} · 文字 ${it.sumNum} 条 · 窗口 ${it.length}×${it.width}"
            } ?: "出厂默认（时间戳 + 通道名）",
        ),
        r(
            "录像计划", videoRecordPlan != null,
            videoRecordPlan?.let {
                if (it.recordEnable == 1) {
                    "启用 · ${it.scheduleSumNum} 天 / ${it.schedules.sumOf { day -> day.segments.size }} 段"
                } else {
                    "未启用"
                }
            } ?: "未启用（出厂默认）",
        ),
        r(
            "报警录像", videoAlarmRecord != null,
            videoAlarmRecord?.let {
                if (it.recordEnable == 1) {
                    "启用 · 码流 ${it.streamNumber} · 延时 ${it.recordTime ?: "(未下发)"}s"
                } else {
                    "未启用"
                }
            } ?: "未启用（出厂默认）",
        ),
        r(
            "报警上报", alarmReport != null,
            (alarmReport ?: AlarmReportConfig.DEFAULT).let {
                "移动侦测${if (it.motionDetection == 1) "开" else "关"} · " +
                    "区域入侵${if (it.fieldDetection == 1) "开" else "关"}"
            },
        ),
        r(
            "基本参数", basicParam != null,
            BasicParamConfig.effective(config, basicParam).let {
                "${it.name} · 心跳 ${it.heartBeatInterval}s × ${it.heartBeatCount}"
            },
        ),
        r(
            "图像抓拍", snapShot != null,
            snapShot?.let { "${it.snapNum} 张" + (it.intervalSeconds?.let { s -> " · 间隔 ${s}s" } ?: "") }
                ?: "平台未配置",
        ),
    )
}

/**
 * 「视频参数属性」摘要行的值 —— **按码流逐路列出**，这是"主/子码流各是什么"在设备屏幕上
 * 唯一的可见处。
 *
 * 取值走 [VideoParamAttribute.effectiveParams]（**与回读应答同一个真源**）：
 * `平台写入值 ?: 出厂派生`。
 * ⛔ 不要在这里按 `config.video` 另拼一份 —— 两处各算一份，平台下发过一次之后
 * 屏幕显示与回读值就会不一致，而两者**看起来都对**，最难查。
 *
 * ⚠️ 行宽有限（`ImageEventRow` 的 value 是单行 + 省略号），这里给的是紧凑写法
 * （`主 1920×1080/25fps/2000k · 子 1280×720/15fps/500k`），**超宽会被省略号截掉** ——
 * 完整值以平台侧视频参数面板为准；屏幕这边只保证"主/子确实是不同档"这件事看得见。
 *
 * ⭐ 档位名按标准对 `StreamNumber` 的定义翻译（A.2.1.13：0-主码流 / 1-子码流 / 2-第三码流），
 * ⛔ 不是 `子${streamNumber}`：那样第 2 路会显示成「子2」，而标准里它是**第三码流**，
 * 与平台面板的行标对不上，排查时会以为是不同的一路。
 */
private fun videoParamSummary(
    config: SimConfig,
    videoParams: Map<Int, VideoParamState>,
): String = VideoParamAttribute.effectiveParams(config, videoParams).joinToString(" · ") { param ->
    // ⛔ `${...}` 不能省成 `$roleLabelOf(...)`：那样只取到函数名，后面当字面量拼进去。
    "${roleLabelOf(param.streamNumber)} ${resolutionLabelOf(param.resolution)}/" +
        "${param.frameRate}fps/${param.videoBitRate}k"
}

/** `StreamNumber` → 标准里的码流名（A.2.1.13：0-主码流 / 1-子码流 / 2-第三码流）。 */
private fun roleLabelOf(streamNumber: Int): String = when (streamNumber) {
    0 -> "主"
    1 -> "子"
    else -> "第${streamNumber + 1}码流"
}

/**
 * 附录 G 的分辨率码值 → 人读档位（`"5"` → `"1280×720"`）。
 *
 * ⛔ 回读应答必须发码值（协议要求），屏幕上要给人看 —— 转换只在这一处。
 * 走 [VideoResolution.gb28181Code] 单一真源；**对不上就原样显示码值**，
 * 不猜一个"最近的档位"（猜错会让人以为设备报了另一个分辨率）。
 * 标准允许的非枚举码值（`WxH`）本来就不在枚举里，会原样透出，这是预期的。
 */
private fun resolutionLabelOf(code: String): String =
    VideoResolution.entries.firstOrNull { it.gb28181Code.toString() == code }?.label ?: code
