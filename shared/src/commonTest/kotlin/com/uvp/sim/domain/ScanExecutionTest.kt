package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自动扫描(GB/T 28181 表 A.10)在**设备侧**的判定测试。
 *
 * 结构与 `CruiseExecutionTest` / 看守位那套一致:被打了星号的是**纯函数** [scanStepAt] 与
 * [scanRateDegPerSec],它们不看时间、不看时钟,所以单测不用真等秒;节拍与边沿(起步/停止)
 * 留在 [SimulatorEngine] 那一侧,靠最后那条**跨层接线用例**守。
 */
class ScanExecutionTest {

    private fun model(
        pan: Float = 0f,
        activeGroup: Int? = 0,
        hasGroup: Boolean = true,
        left: Float? = -30f,
        right: Float? = 45f,
        speed: Int? = 128,
    ) = DeviceControlModel(
        panAngle = pan,
        activeScanGroup = activeGroup,
        scanGroups = if (!hasGroup) {
            emptyMap()
        } else {
            mapOf(
                0 to ScanGroupState(
                    leftBoundary = left?.let { PtzPose(it, 0f, 1f) },
                    rightBoundary = right?.let { PtzPose(it, 0f, 1f) },
                    speed = speed,
                ),
            )
        },
    )

    // ---------- 不动的情形(缺条件时绝不替平台编一段可以扫的区间)----------

    @Test
    fun `平台没启动扫描时不动`() {
        assertNull(scanStepAt(model(activeGroup = null), ScanSweepDirection.TO_RIGHT))
    }

    @Test
    fun `启动的组本机不存在时不动`() {
        assertNull(scanStepAt(model(hasGroup = false), ScanSweepDirection.TO_RIGHT))
    }

    @Test
    fun `只有一侧边界时不动`() {
        // 单边扫描是标准里没有的行为 —— 猜一个"从当前位置到那一侧"等于替平台编边界。
        assertNull(scanStepAt(model(left = null), ScanSweepDirection.TO_RIGHT))
        assertNull(scanStepAt(model(right = null), ScanSweepDirection.TO_LEFT))
    }

    @Test
    fun `边界重合或设反时不动`() {
        assertNull(scanStepAt(model(left = 10f, right = 10f), ScanSweepDirection.TO_RIGHT))
        assertNull(scanStepAt(model(left = 40f, right = -10f), ScanSweepDirection.TO_RIGHT))
        // 刚好差一点儿,仍在下限内
        assertNull(scanStepAt(model(left = 0f, right = 1f), ScanSweepDirection.TO_RIGHT))
    }

    @Test
    fun `跨度刚好到下限就可以扫`() {
        val plan = scanStepAt(
            model(pan = 0f, left = 0f, right = MIN_SCAN_SPAN_DEGREES),
            ScanSweepDirection.TO_RIGHT,
        )
        assertEquals(0, plan?.groupNum)
    }

    // ---------- 方向与速率 ----------

    @Test
    fun `中途不掉头 — 继续朝当前方向并以带符号速率驱动`() {
        val plan = scanStepAt(model(pan = 0f), ScanSweepDirection.TO_RIGHT)
        assertTrue(plan != null)
        assertFalse(plan.turnedAround)
        assertEquals(ScanSweepDirection.TO_RIGHT, plan.direction)
        assertTrue(plan.panRateDegPerSec > 0f, "向右应为正速率(panAngle 增大)")
    }

    @Test
    fun `到右边界掉头向左 — 速率为负`() {
        val plan = scanStepAt(model(pan = 45f), ScanSweepDirection.TO_RIGHT)
        assertTrue(plan != null)
        assertTrue(plan.turnedAround)
        assertEquals(ScanSweepDirection.TO_LEFT, plan.direction)
        assertTrue(plan.panRateDegPerSec < 0f, "掉头后应为负速率")
    }

    @Test
    fun `到左边界掉头向右 — 速率为正`() {
        val plan = scanStepAt(model(pan = -30f), ScanSweepDirection.TO_LEFT)
        assertTrue(plan != null)
        assertTrue(plan.turnedAround)
        assertEquals(ScanSweepDirection.TO_RIGHT, plan.direction)
        assertTrue(plan.panRateDegPerSec > 0f)
    }

    @Test
    fun `起点已在边界之外 — 立刻朝另一侧走`() {
        // 平台先转到底、再设边界时不是这条路径,但"边界设在当前位置之内"(设备被本地面板
        // 转过)会出现。此时不该继续往越界的方向推,而是马上掉头。
        val plan = scanStepAt(model(pan = 60f), ScanSweepDirection.TO_RIGHT)
        assertTrue(plan != null)
        assertEquals(ScanSweepDirection.TO_LEFT, plan.direction)
    }

    // ---------- 速度映射(标准没给量纲 ⇒ 这里是本仓的建模选择)----------

    @Test
    fun `速度映射单调 — 快档速率更大`() {
        assertTrue(scanRateDegPerSec(4095) > scanRateDegPerSec(120))
        assertTrue(scanRateDegPerSec(120) > scanRateDegPerSec(1))
        assertEquals(SCAN_MAX_RATE_DEG_PER_SEC, scanRateDegPerSec(MAX_SCAN_SPEED))
        // 手造报文超过满量程时按满量程算,别把转速推到天上
        assertEquals(SCAN_MAX_RATE_DEG_PER_SEC, scanRateDegPerSec(9999))
    }

    @Test
    fun `平台没下发过速度时取出厂默认档`() {
        assertEquals(scanRateDegPerSec(DEFAULT_SCAN_SPEED), scanRateDegPerSec(null))
        // 0 / 负数都不是合法速度(平台侧校验 1-4095)⇒ 与"没下发"同一处理,而不是"停住"
        assertEquals(scanRateDegPerSec(DEFAULT_SCAN_SPEED), scanRateDegPerSec(0))
        assertEquals(scanRateDegPerSec(DEFAULT_SCAN_SPEED), scanRateDegPerSec(-5))
    }

    @Test
    fun `掉头提前量按速率折算 — 快档更早掉头`() {
        // 渲染端回写姿态是 ~166ms 一次、本拍再滞后 150ms ⇒ 判到边界时已越过约 0.3 秒的行程。
        // 提前量按速率算 ⇒ 同一位置慢档还不到边界、快档已经该掉头了。
        val slow = scanStepAt(model(pan = 44.5f, speed = 1), ScanSweepDirection.TO_RIGHT)
        assertFalse(slow!!.turnedAround, "慢档不该在离边界 0.5° 处就掉头")
        val fast = scanStepAt(model(pan = 44.5f, speed = MAX_SCAN_SPEED), ScanSweepDirection.TO_RIGHT)
        assertTrue(fast!!.turnedAround, "满档提前量 27°(90°/s × 0.3s)应当已经掉头")
    }

    @Test
    fun `越界边界被钳到水平行程极限`() {
        // 边界只可能来自设备自己的姿态快照(渲染端已钳在 ±180)。存档/手造数据越界时不钳的话,
        // 扫描永远到不了那个位置,会卡在 ±180 上单向空推。
        val plan = scanStepAt(model(pan = 0f, left = -400f, right = 400f), ScanSweepDirection.TO_RIGHT)
        assertEquals(ScanSweepDirection.TO_RIGHT, plan?.direction)
        val atLimit = scanStepAt(model(pan = 180f, left = -400f, right = 400f), ScanSweepDirection.TO_RIGHT)
        assertTrue(atLimit!!.turnedAround, "到 +180 就该掉头,而不是继续往 400 推")
    }

    // ---------- 跨层接线:真实帧 → 状态 → 决策 ----------

    /**
     * 单测解码器、单测决策函数**都抓不到"接错字段"**这类错(例如边界没落进 `scanGroups`、
     * 或者启动只置了 `lastCommand` 没置 `activeScanGroup`)。这条用例用平台**真实下发的四帧**
     * 走完整条链,再让决策函数算出方向与速率 —— 与
     * `DeviceControlDispatcherTest` 里那几条"帧 → 状态"的用例互补:它守前半段,这条守接线。
     */
    @Test
    fun `真实帧序列 设边界-设速度-开始扫描 之后能真的扫起来`() {
        val config = SimConfig(
            server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
            device = DeviceConfig(
                deviceId = "34020000001320000001",
                videoChannelId = "34020000001310000001",
                alarmChannelId = "34020000001340000001",
                username = "34020000001320000001",
                password = "test-password",
            ),
        )
        val state = MutableStateFlow(DeviceControlModel(panAngle = -20f))
        val dispatcher = DeviceControlDispatcher(state, config, NoopDeviceControlActions)

        // 平台四条真实帧(2026-09-20 从 gb_sip_trace_message 解密取得)
        dispatcher.dispatch("<C><PTZCmd>A50F01890001003F</PTZCmd></C>")  // 设左边界(此刻 pan=-20)
        state.value = state.value.copy(panAngle = 40f)
        dispatcher.dispatch("<C><PTZCmd>A50F018900020040</PTZCmd></C>")  // 设右边界(此刻 pan=+40)
        dispatcher.dispatch("<C><PTZCmd>A50F018A007800B7</PTZCmd></C>")  // 速度 120
        dispatcher.dispatch("<C><PTZCmd>A50F01890000003E</PTZCmd></C>")  // 开始扫描(0 号组)

        val group = state.value.scanGroups[0]
        assertEquals(-20f, group?.leftBoundary?.pan)
        assertEquals(40f, group?.rightBoundary?.pan)
        assertEquals(120, group?.speed)
        assertEquals(0, state.value.activeScanGroup)

        // ⭐ 现场就是这样:操作员把镜头转到"想当右边界"的位置再设边界 ⇒ 启动那一刻镜头**正贴在右边界上**。
        //    所以这里先断言最容易被写错的一拍:必须立刻掉头向左。
        //    (曾经这条断言写成"应该向右扫",测试红了 —— 不是实现错,而是用例忘了设完右边界
        //     之后镜头还停在 40°。反过来也说明:贴边启动若判成"还没到"继续往右推,会推到行程极限再回来。)
        val onBoundary = scanStepAt(state.value, ScanSweepDirection.TO_RIGHT)
        assertTrue(onBoundary != null, "边界与启动都落库之后,决策函数必须能算出横扫")
        assertTrue(onBoundary.turnedAround, "启动时已贴右边界,第一拍就该掉头")
        assertEquals(ScanSweepDirection.TO_LEFT, onBoundary.direction)
        assertTrue(onBoundary.panRateDegPerSec < 0f, "掉头向左 ⇒ 速率为负,渲染端才会反向积分")
        assertEquals(0, onBoundary.groupNum)

        // 把镜头挪到行程中间:两组边界分列两侧 ⇒ 正常向右扫
        val mid = scanStepAt(state.value.copy(panAngle = 0f), ScanSweepDirection.TO_RIGHT)
        assertTrue(mid != null)
        assertFalse(mid.turnedAround)
        assertEquals(ScanSweepDirection.TO_RIGHT, mid.direction)
        assertTrue(mid.panRateDegPerSec > 0f)
        // 速率必须真的来自那条 `0x8A` 帧(120),而不是默认档 —— 否则"设扫描速度"在设备上没有落点
        assertEquals(scanRateDegPerSec(120), mid.panRateDegPerSec)

        // 扫到右边界就掉头 —— 用的是**真帧里落下来的** 40°,不是测试自己造的数
        val flipped = scanStepAt(
            state.value.copy(panAngle = 40f),
            ScanSweepDirection.TO_RIGHT,
        )
        assertTrue(flipped!!.turnedAround)
        assertEquals(ScanSweepDirection.TO_LEFT, flipped.direction)
    }
}

/** 只用于 [scanStepAt] 的接线用例:扫描链路一个副作用都不该触发。 */
private object NoopDeviceControlActions : DeviceControlActions {
    override suspend fun reboot() = Unit
    override suspend fun snapshot() = Unit
    override fun requestKeyFrame() = Unit
    override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) = Unit
    override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) = Unit
    override fun formatStorageCard(cardIndex: Int) = Unit
}
