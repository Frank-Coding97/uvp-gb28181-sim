package com.uvp.sim.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 巡航执行判定 —— `0x88 开始巡航` 之后设备自己该怎么走（GB/T 28181 表 A.8 只给了"开始 + 组号"，
 * 点位链、停留时间是 `0x84`/`0x87` 攒下来的，执行是设备侧的事）。
 *
 * 判定是纯函数（不读时钟、不接时间参数），所以这里直接喂步号，不用真等 `dwellTime` ——
 * 默认停留 30 秒，真等的话这组用例要跑好几分钟。节拍与起步/停车边沿在 [SimulatorEngine] 里，
 * 不在这一层。
 */
class CruiseExecutionTest {

    private val p2 = PtzPose(pan = 10f, tilt = 0f, zoom = 1f)
    private val p3 = PtzPose(pan = 20f, tilt = 5f, zoom = 1.5f)
    private val p4 = PtzPose(pan = 30f, tilt = -5f, zoom = 2f)

    private fun cruising(
        trackNum: Int? = 1,
        points: List<Int> = listOf(2, 3, 4),
        dwellTime: Int? = 5,
        presets: Map<Int, PtzPose> = mapOf(2 to p2, 3 to p3, 4 to p4),
    ) = DeviceControlModel(
        presets = presets,
        cruiseTracks = mapOf(
            1 to CruiseTrackState(points = points, speed = 128, dwellTime = dwellTime),
        ),
        activeCruiseTrack = trackNum,
    )

    @Test
    fun `未启动巡航时不动作`() {
        assertNull(cruiseStepAt(cruising(trackNum = null), 0))
    }

    @Test
    fun `平台启动的组号本机没有时不动作`() {
        // 轨迹还没配完(0x84 一条都没到),或已被 0x85 删空。凭空造轨迹比不动更糟。
        assertNull(cruiseStepAt(cruising(trackNum = 9), 0))
    }

    @Test
    fun `按点位链顺序走,下标 0 就是链上第一个点`() {
        val model = cruising()
        assertEquals(2, cruiseStepAt(model, 0)?.presetIndex)
        assertEquals(p2, cruiseStepAt(model, 0)?.target)
        assertEquals(3, cruiseStepAt(model, 1)?.presetIndex)
        assertEquals(4, cruiseStepAt(model, 2)?.presetIndex)
        assertEquals(0, cruiseStepAt(model, 0)?.pointIndex)
        assertEquals(3, cruiseStepAt(model, 0)?.pointCount)
    }

    @Test
    fun `走完最后一个点回到第一个点继续 —— 巡航是循环的`() {
        // 标准里设备一直巡到收到停止指令为止,不是"走完一遍就停"。
        val model = cruising()
        assertEquals(2, cruiseStepAt(model, 3)?.presetIndex)
        assertEquals(0, cruiseStepAt(model, 3)?.pointIndex)
        assertEquals(3, cruiseStepAt(model, 4)?.presetIndex)
        assertEquals(1, cruiseStepAt(model, 4)?.pointIndex)
        assertEquals(4, cruiseStepAt(model, 5)?.presetIndex)
    }

    @Test
    fun `本机缺失的预置位逐个跳过,剩下的照常按顺序走`() {
        // 平台侧不知道设备上哪几个预置位真的存住了(预置位 SET 可能失败)。缺一个就整条卡死
        // 比跳过更糟 —— 而且卡死的现象是"点了启动没反应",跟没实现执行一模一样,极难归因。
        val model = cruising(presets = mapOf(2 to p2, 4 to p4))
        assertEquals(2, cruiseStepAt(model, 0)?.presetIndex)
        assertEquals(4, cruiseStepAt(model, 1)?.presetIndex)
        assertEquals(2, cruiseStepAt(model, 2)?.presetIndex, "跳过后只剩 2 个点,第 3 步该绕回来")
        assertEquals(2, cruiseStepAt(model, 0)?.pointCount, "pointCount 报的是**可走的**点数")
    }

    @Test
    fun `一个可用点都没有时不动作,也不造坐标`() {
        // 同 PresetHandler / decideHomePositionReturn 的口径:引用不存在的预置位时什么都不做。
        val model = cruising(presets = mapOf(1 to p2))
        assertNull(cruiseStepAt(model, 0))
    }

    @Test
    fun `点位链为空时不动作`() {
        assertNull(cruiseStepAt(cruising(points = emptyList()), 0))
    }

    @Test
    fun `停留时间未下发时用出厂默认,下发值优先`() {
        // 这个默认值必须与查询应答里回的那个是同一个(见 DEFAULT_CRUISE_DWELL_SECONDS 的注释):
        // 写两份会演成"界面显示停留 30 秒、设备 5 秒跳一个点"。
        assertEquals(30, cruiseStepAt(cruising(dwellTime = null), 0)?.dwellSeconds)
        assertEquals(5, cruiseStepAt(cruising(dwellTime = 5), 0)?.dwellSeconds)
    }

    @Test
    fun `停留时间为 0 时钳到 1 秒,不许变成空转`() {
        // 平台写侧只允许 1-4095,但一条手造的报文不该把节拍打成死循环。
        assertEquals(1, cruiseStepAt(cruising(dwellTime = 0), 0)?.dwellSeconds)
    }

    @Test
    fun `巡航进行中不做看守位自动归位`() {
        // 停留期间设备"没在动",而 ResetTime 允许小到 10 秒 —— 不闸住的话看守位会在巡航中途
        // 把镜头拽回看守位,下一拍巡航又把它转走,两个设备自主行为抢同一个 pose。
        val model = cruising().copy(
            homePositionEnabled = true,
            homePositionPresetIndex = 2,
            homePositionResetTime = 10,
        )
        assertNull(
            decideHomePositionReturn(model, idleSeconds = 999, alreadyReturned = false),
            "巡航期间不得自动归位",
        )
        // 停掉巡航(全零停止帧会把 activeCruiseTrack 清掉)之后,归位恢复。
        val stopped = model.copy(activeCruiseTrack = null)
        assertEquals(2, decideHomePositionReturn(stopped, idleSeconds = 10, alreadyReturned = false)?.presetIndex)
    }
}
