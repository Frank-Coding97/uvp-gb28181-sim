package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.gb28181.DeviceConfigState
import com.uvp.sim.gb28181.FrameMirrorState
import com.uvp.sim.gb28181.FrontOsdState
import com.uvp.sim.gb28181.OsdTextItem
import com.uvp.sim.gb28181.PictureMaskRegion
import com.uvp.sim.gb28181.PictureMaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「设备配置族」→ UI 视图态的映射守卫（`DeviceConfigMapper`）。
 *
 * 三条规矩各有一组用例：
 *
 *  1. **遮挡保留协议原始像素**（2026-09-19 起不再归一化）：
 *     遮挡已从 Compose 画布搬去"烧进真实视频流"，那条链路的归一化在
 *     `VideoMaskOverlay.of()` 里做；设备屏幕上要显示的是"平台发的坐标是多少"，
 *     再除一次会让屏幕上的数和平台上的数**对不上**。
 *  2. **OSD 坐标按它自己声明的窗口尺寸归一化**（只对"要往画布上画"的东西做）。
 *  3. **画布叠层只画平台下发过的**，不回退出厂默认 ——
 *     出厂默认的 OSD 已经由本机三层 OSD 烧进视频流，回退就是同一行字重合两遍。
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
    ) = DeviceControlModel(deviceConfigs = deviceConfigs)
        .toDeviceConfigDto(config(resolution))

    // ===== ① 遮挡：保留协议原始像素 =====

    /**
     * ⛔ 平台给的是**视频帧绝对像素**，这里必须**原样**透出。
     *
     * 屏幕上的数字要能与平台侧「画面遮挡」面板逐格对上 —— 除一次帧宽高之后
     * 显示成 `0.5` 这种值，操作员既没法核对、也没法判断平台到底发了什么。
     * （顺带说明为什么不能按 `config.video.resolution` 归一化：平台填的可能是
     * 另一档分辨率下的坐标，除完就成了一个谁都对不上的数。）
     */
    @Test fun pictureMask_keepsProtocolPixelsVerbatim() {
        val view = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(
                    on = 1,
                    regions = listOf(
                        PictureMaskRegion(1, 0, 0, 960, 540),
                        PictureMaskRegion(3, 960, 540, 1920, 1080),
                    ),
                )
            )
        ).pictureMask

        assertTrue(view.on)
        assertTrue(view.configured)
        assertEquals(2, view.rects.size)
        assertEquals(1, view.rects[0].seq)
        assertEquals(0, view.rects[0].left)
        assertEquals(960, view.rects[0].right, "不能被除以帧宽变成 0.5")
        assertEquals(540, view.rects[0].bottom, "不能被除以帧高变成 0.5")
        // 稀疏序号(1,3)原样保留 —— 屏幕按 Seq 显示编号，重排会让它和平台列表对不上。
        assertEquals(3, view.rects[1].seq)
        assertEquals(1920, view.rects[1].right)
    }

    /** 人读串就是协议里那两个角点，逗号分隔、无空格（与 `PictureMaskRegion.pointLiteral` 同形）。 */
    @Test fun pictureMask_displayLabelMatchesProtocolPointLiteral() {
        val view = dto(
            DeviceConfigState(pictureMask = PictureMaskState(on = 1, regions = listOf(PictureMaskRegion(2, 20, 30, 50, 60))))
        ).pictureMask
        assertEquals("20,30 → 50,60", view.rects.single().displayLabel)
    }

    /**
     * ⛔ 「平台从没配过」与「平台配过、但把遮挡关了」必须能分开 —— 两者的 `on` 都是 false，
     * 但设备屏幕上该说不同的话（前者＝设备出厂就这样；后者＝有人刚把它关了）。
     * 这是本仓「没查过 / 查了但为空」必须分两态的同一条规矩。
     */
    @Test fun pictureMask_distinguishesNeverConfiguredFromPushedOff() {
        val never = dto(DeviceConfigState()).pictureMask
        assertFalse(never.configured, "平台一次都没配过")
        assertFalse(never.on)

        val pushedOff = dto(
            DeviceConfigState(pictureMask = PictureMaskState(on = 0, regions = emptyList()))
        ).pictureMask
        assertTrue(pushedOff.configured, "配过（配成关）与从没配过要能分开")
        assertFalse(pushedOff.on)
    }

    // ===== ② 遮挡不再进画布叠层 =====

    /**
     * ⛔⛔ 这条钉的是 2026-09-19 的形态变更本身：**遮挡开着、有区域，画布叠层也必须为空**。
     *
     * 遮挡已经改为烧进真实视频流（`MaskPass` / `IosFrameProcessor`）。如果哪天有人
     * "顺手"把 `pictureMask` 加回 `hasCanvasContent`，画布上会重新出现一份**假的**黑块 ——
     * 而它和真正推给平台的画面**不是同一条链路**，等于让"设备屏幕上看到的"与
     * "平台点播看到的"说的不是同一件事。
     */
    @Test fun canvasLayerIgnoresPictureMaskEvenWhenItIsOn() {
        val config = dto(
            DeviceConfigState(
                pictureMask = PictureMaskState(on = 1, regions = listOf(PictureMaskRegion(1, 0, 0, 100, 100)))
            )
        )
        assertTrue(config.pictureMask.on, "遮挡确实是开着的")
        assertEquals(1, config.pictureMask.rects.size)
        assertFalse(config.hasCanvasContent, "遮挡不该再让画布叠层挂载")
    }

    /** 平台没下发过 ⇒ 画布上什么都不画（本机三层 OSD 已经烧过出厂默认那一份）。 */
    @Test fun canvasStaysEmptyWhenPlatformNeverPushedAnything() {
        val config = dto(DeviceConfigState())
        assertEquals(emptyList(), config.pictureMask.rects)
        assertEquals(emptyList(), config.osdLines)
        assertNull(config.frameMirror, "平台没配过必须是 null，不能兜成 0")
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

    /**
     * 翻转只画**平台推送过**的，且**保留 `null`**（没配过 ≠ 配了 0）。
     *
     * ⛔ 不兜成 0（也不用同为 0 的 `FrameMirrorConfig.DEFAULT`）：设备屏幕上要说的是
     * 「平台没配过」还是「平台把它关掉了」—— 两者画面表现一样，但对操作员是两条信息。
     * 这是 HUD 图像页「画面镜像」四卡片的全灰态与「不启用」选中态的**唯一**判据。
     *
     * ⛔ 翻转**既不**走画布叠层、**也不**由画布施加 —— 它是**真流**的变换
     * （`FrameMirrorTransform` 走采集链路：Android `CameraTexturePass` / iOS `IosFrameProcessor`）。
     * 本 DTO 只是把"平台配没配过"带给 HUD 四卡片。
     */
    @Test fun frameMirrorOnlyReflectsPlatformPush() {
        assertNull(dto(DeviceConfigState()).frameMirror, "平台没配过 ⇒ null")
        assertEquals(2, dto(DeviceConfigState(frameMirror = FrameMirrorState(2))).frameMirror)
        assertEquals(0, dto(DeviceConfigState(frameMirror = FrameMirrorState(0))).frameMirror,
            "平台配了 0 ⇒ 0（不是 null）—— 「不启用」必须与「没配过」分得开")
        assertFalse(
            dto(DeviceConfigState(frameMirror = FrameMirrorState(2))).hasCanvasContent,
            "镜像不走画布叠层：它是真流上的变换，画布一侧已明确不参与（2026-09-20）",
        )
    }
}
