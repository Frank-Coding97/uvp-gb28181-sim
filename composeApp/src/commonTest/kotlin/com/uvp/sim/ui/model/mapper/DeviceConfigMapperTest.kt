package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.gb28181.AlarmReportState
import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.FrameMirrorState
import com.uvp.sim.gb28181.FrontOsdState
import com.uvp.sim.gb28181.OsdTextItem
import com.uvp.sim.gb28181.PictureMaskRegion
import com.uvp.sim.gb28181.PictureMaskState
import com.uvp.sim.gb28181.SnapShotState
import com.uvp.sim.gb28181.VideoAlarmRecordState
import com.uvp.sim.gb28181.VideoParamAttribute
import com.uvp.sim.gb28181.VideoParamState
import com.uvp.sim.gb28181.VideoRecordPlanState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「设备配置族」→ UI 视图态的映射守卫（`DeviceConfigMapper`）。
 *
 * 两条规矩各有一组用例，它们守的都是**静默错位**（矩形仍然画出来、只是位置不对）：
 *
 *  1. 像素 → 归一化**只在 Mapper 做一次**（渲染层只管乘画布尺寸）。
 *  2. 画布叠层**只画平台下发过的**，不回退出厂默认 ——
 *     出厂默认的 OSD 在画布上已经由本机三层 OSD 画了一遍，回退就是同一行字重合两遍。
 */
class DeviceConfigMapperTest {

    private fun config(resolution: VideoResolution = VideoResolution.FHD_1080P) = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            name = "我的设备",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
        ),
        keepaliveIntervalSeconds = 30,
        maxKeepaliveTimeouts = 5,
        video = VideoProfile(resolution = resolution),
    )

    private fun dto(
        deviceConfigs: DeviceConfigState,
        resolution: VideoResolution = VideoResolution.FHD_1080P,
        videoParams: Map<Int, VideoParamState> = emptyMap(),
    ) = DeviceControlModel(deviceConfigs = deviceConfigs, videoParams = videoParams)
        .toDeviceConfigDto(config(resolution))

    // ===== ① 归一化：像素 → 0~1，只做一次 =====

    @Test fun pictureMask_normalizesByFrameSize() {
        val view = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(
                    on = 1,
                    regions = listOf(PictureMaskRegion(1, 0, 0, 960, 540), PictureMaskRegion(2, 960, 540, 1920, 1080)),
                )
            )
        ).pictureMask
        assertTrue(view.on)
        assertEquals(2, view.rects.size)
        assertEquals(0f, view.rects[0].left)
        assertEquals(0f, view.rects[0].top)
        assertEquals(0.5f, view.rects[0].right)
        assertEquals(0.5f, view.rects[0].bottom)
        assertEquals(1f, view.rects[1].right)
        assertEquals(1f, view.rects[1].bottom)
    }

    /** 平台给的像素超出当前帧时钳在画布内 —— 画到画布外面看不见，等于"配了没效果"。 */
    @Test fun pictureMask_clampsOutOfFrameCoordinates() {
        val view = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(
                    on = 1,
                    regions = listOf(PictureMaskRegion(1, 0, 0, 4000, 5000)),
                )
            )
        ).pictureMask
        assertEquals(1f, view.rects.single().right)
        assertEquals(1f, view.rects.single().bottom)
    }

    /** 平台没下发过 ⇒ 画布上什么都不画（本机三层 OSD 已经画过出厂默认那一份）。 */
    @Test fun canvasStaysEmptyWhenPlatformNeverPushedAnything() {
        val config = dto(DeviceConfigState())
        assertEquals(emptyList(), config.pictureMask.rects)
        assertEquals(emptyList(), config.osdLines)
        assertEquals(0, config.frameMirror)
        assertFalse(config.hasCanvasContent)
    }

    /**
     * ⛔ OSD 的画布坐标按**它自己声明的窗口尺寸**归一化，不用 `config.video.resolution`。
     * 标准里 `Length`/`Width` 就是"这块 OSD 的坐标系有多大"，平台按自己那份填的；
     * 用配置分辨率归一化会在平台填了别的窗口尺寸时整体偏移。
     */
    @Test fun osdLines_normalizeByItsOwnDeclaredWindowNotConfigResolution() {
        val view = dto(
            DeviceConfigState(
                frontOsd = FrontOsdState(
                    length = 640, width = 480,
                    timeX = 0, timeY = 0,
                    items = listOf(OsdTextItem("通道1", 320, 240)),
                )
            )
        )
        assertEquals(0.5f, view.osdLines.single().x, "320 / 640，不是 320 / 1920")
        assertEquals(0.5f, view.osdLines.single().y, "240 / 480，不是 240 / 1080")
    }

    /** 平台声明窗口尺寸为 0/缺失时退回帧尺寸兜底，但不能除出 NaN/Infinity。 */
    @Test fun osdLines_fallBackToFrameSizeWhenWindowIsZero() {
        val view = dto(
            DeviceConfigState(
                frontOsd = FrontOsdState(
                    length = 0, width = 0,
                    timeX = 0, timeY = 0,
                    items = listOf(OsdTextItem("通道1", 960, 540)),
                )
            )
        )
        assertEquals(0.5f, view.osdLines.single().x)
        assertEquals(0.5f, view.osdLines.single().y)
    }

    /** 翻转只画**平台推送过**的：没配过保持 0（而不是回退到同为 0 的 `FrameMirrorConfig.DEFAULT`）。 */
    @Test fun frameMirrorOnlyReflectsPlatformPush() {
        assertEquals(0, dto(DeviceConfigState()).frameMirror)
        val pushed = dto(DeviceConfigState(frameMirror = FrameMirrorState(2))).frameMirror
        assertEquals(2, pushed)
        assertTrue(dto(DeviceConfigState(frameMirror = FrameMirrorState(2))).hasCanvasContent)
    }

    // ===== ② 摘要行：恒定 9 条、顺序固定、能区分"平台配的"与"出厂默认" =====

    /**
     * ⭐ 行**恒定 9 条、顺序固定**。行数随配置变化会让面板每次下发后重排，
     * 操作员反而看不出改了什么。
     *
     * ⚠️ 9 = 附录 A 设备配置族的 12 个 `ConfigType` − SVAC 编/解码 2 项（本仓不做）
     * − `VideoParamOpt`（能力范围声明、由回读派生，不是"一项待配配置"）。
     * 别看到"8 项在平台面板上"就以为这里该是 8 —— 平台面板列的是**待配置项**，
     * 这里列的是**这一族的全部类型**，两者口径不同。
     */
    @Test fun summaryRowsAreAlwaysNineInFixedOrder() {
        val labels = dto(DeviceConfigState()).rows.map { it.label }
        assertEquals(9, labels.size)
        assertEquals(
            listOf(
                "视频参数属性", "画面遮挡", "画面翻转", "前端 OSD", "录像计划",
                "报警录像", "报警上报", "基本参数", "图像抓拍",
            ),
            labels,
        )
    }

    @Test fun summaryRowsFlagFactoryDefaultsAsNotFromPlatform() {
        val rows = dto(DeviceConfigState()).rows.associateBy { it.label }
        assertTrue(rows.values.none { it.fromPlatform }, "一个都没配过时不能有任何行标成'平台配的'")
        assertEquals("关闭（出厂默认）", rows.getValue("画面遮挡").value)
        assertEquals("不启用（出厂默认）", rows.getValue("画面翻转").value)
        assertEquals("出厂默认（时间戳 + 通道名）", rows.getValue("前端 OSD").value)
        assertEquals("未启用（出厂默认）", rows.getValue("录像计划").value)
        assertEquals("未启用（出厂默认）", rows.getValue("报警录像").value)
        assertEquals("平台未配置", rows.getValue("图像抓拍").value)
        // ⭐ 报警上报的出厂默认照设备真实行为报（移动侦测真的开着、区域入侵真的没有），
        //   不是"两项都关"——那会让面板显示"报警已关闭"而设备正在上报报警。
        assertEquals("移动侦测开 · 区域入侵关", rows.getValue("报警上报").value)
        // ⭐ 基本参数的出厂默认不是常量，是设备此刻真的在跑的值。
        assertEquals("我的设备 · 心跳 30s × 5", rows.getValue("基本参数").value)
    }

    // ===== ③ 视频参数属性：常驻行 + 主/子逐路 =====

    /**
     * ⭐⛔ 「视频参数属性」是这一族里**唯一永远有值**的项（`平台写入 ?: 出厂派生`），
     * 所以它 `alwaysVisible`、且**平台一次都没配过时也要有值**。
     *
     * 这条用例守的是"主/子码流在设备屏幕上可见"这件事本身：如果它跟着 `fromPlatform`
     * 被过滤掉，平台没配过时这一行就**永远不显示** —— 而"设备现在主/子码流各是什么"
     * 恰恰是这一项存在的唯一理由（平台没配过时它照样有一组生效值）。
     */
    @Test fun videoParamRowIsAlwaysVisibleEvenWhenPlatformNeverConfigured() {
        val rows = dto(DeviceConfigState()).rows
        val row = rows.single { it.label == "视频参数属性" }
        assertTrue(row.alwaysVisible, "常驻位：不能跟着 fromPlatform 被过滤")
        assertFalse(row.fromPlatform, "平台确实没配过，这一位要诚实")
        assertEquals(
            "主 1920×1080/25fps/2000k · 子 1280×720/15fps/500k",
            row.value,
            "取的是出厂派生：1080P@25fps/2000k，子码流降一档 + 封顶 15fps + 码率 1/4",
        )
    }

    /** 平台下发过则显示平台值，并且**同一份真源**（`effectiveParams`）—— 屏幕与回读不能各算一份。 */
    @Test fun videoParamRowPrefersPlatformValue() {
        val pushed = VideoParamState(
            streamNumber = 0,
            videoFormat = VideoParamAttribute.VIDEO_FORMAT_H264,
            resolution = "4",
            frameRate = "30",
            bitRateType = VideoParamAttribute.BIT_RATE_TYPE_VBR,
            videoBitRate = "8192",
        )
        val row = dto(DeviceConfigState(), videoParams = mapOf(0 to pushed))
            .rows.single { it.label == "视频参数属性" }
        assertTrue(row.fromPlatform)
        assertEquals("主 640×480/30fps/8192k · 子 1280×720/15fps/500k", row.value)
    }

    /**
     * ⭐ `StreamNumber` 的档位名照标准翻译（A.2.1.13：0-主码流 / 1-子码流 / 2-第三码流）。
     * ⛔ 不是 `子${streamNumber}` —— 那样第 2 路会显示成「子2」，与平台面板的行标对不上。
     */
    @Test fun videoParamRowNamesThirdStreamPerStandard() {
        val row = dto(DeviceConfigState(), videoParams = mapOf(2 to param(streamNumber = 2)))
            .rows.single { it.label == "视频参数属性" }
        assertTrue(row.value.contains("第3码流 "), "StreamNumber=2 应显示为第三码流：${row.value}")
    }

    /**
     * ⛔ 分辨率**码值**（协议口径）与**人读档位**（屏幕口径）之间的转换只在这一处。
     * 对不上枚举就原样透出码值 —— 不猜一个"最近的档位"（猜错会让人以为设备报了另一个分辨率）。
     */
    @Test fun videoParamRowPassesThroughUnknownResolutionCodeVerbatim() {
        val row = dto(DeviceConfigState(), videoParams = mapOf(0 to param(resolution = "1920x1080")))
            .rows.single { it.label == "视频参数属性" }
        assertTrue(row.value.startsWith("主 1920x1080/"), "非枚举码值原样透出：${row.value}")
    }

    private fun param(
        streamNumber: Int = 0,
        resolution: String = "5",
    ) = VideoParamState(
        streamNumber = streamNumber,
        videoFormat = VideoParamAttribute.VIDEO_FORMAT_H264,
        resolution = resolution,
        frameRate = "25",
        bitRateType = VideoParamAttribute.BIT_RATE_TYPE_CBR,
        videoBitRate = "2000",
    )

    @Test fun summaryRowsMarkPlatformConfiguredOnes() {
        val rows = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(on = 1, regions = listOf(PictureMaskRegion(1, 0, 0, 10, 10))),
                frameMirror = FrameMirrorState(1),
                frontOsd = FrontOsdState(length = 1920, width = 1080, timeX = 1, timeY = 2, items = emptyList()),
                videoRecordPlan = VideoRecordPlanState(recordEnable = 1, schedules = emptyList(), streamNumber = 0),
                videoAlarmRecord = VideoAlarmRecordState(recordEnable = 1, recordTime = 30, streamNumber = 0),
                alarmReport = AlarmReportState(motionDetection = 0, fieldDetection = 1),
                snapShot = SnapShotState(snapNum = 3, intervalSeconds = 2, uploadUrl = "u", sessionId = "s"),
            )
        ).rows.associateBy { it.label }

        assertTrue(rows.getValue("画面遮挡").fromPlatform)
        assertEquals("开启 · 1 区", rows.getValue("画面遮挡").value)
        assertEquals("水平镜像（左右翻转）", rows.getValue("画面翻转").value)
        assertEquals("时间开 · 文字 0 条 · 窗口 1920×1080", rows.getValue("前端 OSD").value)
        assertEquals("启用 · 0 天 / 0 段", rows.getValue("录像计划").value)
        assertEquals("启用 · 码流 0 · 延时 30s", rows.getValue("报警录像").value)
        assertEquals("移动侦测关 · 区域入侵开", rows.getValue("报警上报").value)
        assertEquals("3 张 · 间隔 2s", rows.getValue("图像抓拍").value)
        // 只有"基本参数"这次没配 → 仍然 false
        assertFalse(rows.getValue("基本参数").fromPlatform)
    }

    /** 平台下发过但把功能关了（`on=0` / `recordEnable=0`）要显示"关闭/未启用"而**不是**"出厂默认"。 */
    @Test fun summaryRowsDistinguishPushedOffFromFactoryDefault() {
        val rows = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(on = 0, regions = emptyList()),
                videoAlarmRecord = VideoAlarmRecordState(recordEnable = 0, streamNumber = 0),
            )
        ).rows.associateBy { it.label }
        assertEquals("关闭", rows.getValue("画面遮挡").value)
        assertTrue(rows.getValue("画面遮挡").fromPlatform, "平台配过（配成关）与从没配过要能分开")
        assertEquals("未启用", rows.getValue("报警录像").value)
        assertTrue(rows.getValue("报警录像").fromPlatform)
    }
}
