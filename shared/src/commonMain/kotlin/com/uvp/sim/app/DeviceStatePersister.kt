package com.uvp.sim.app

import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 设备状态的**节流落盘**。
 *
 * **为什么不能"状态一变就写"**:姿态是高频量 —— 3D 场景每 ~166ms 回写一次
 * ([com.uvp.sim.domain.SimulatorEngine.updatePoseFromRender]),直接落盘等于每秒写 6 次
 * DataStore / NSUserDefaults。这里用「最后一次变化之后静默 [intervalMs] 才写」的防抖语义,
 * 常态写盘频率被压到与用户操作节奏同阶。
 *
 * **为什么是防抖而不是"固定节拍轮询"**:轮询版本写起来更直白,但它会在协程调度器里留下一个
 * **永不结束**的 `delay` 链。本仓单测用 `runTest`,收尾时会把调度器里所有待执行任务推进到底
 * —— 那个无限节拍会把测试挂死。防抖只在"确实有变化"时留一个 [intervalMs] 的计时器,
 * 上游静默时协程纯挂起、调度器零任务。
 *
 * **姿态每 166ms 回写会不会让防抖永不触发**:不会。[DeviceControlModel] 是 data class,
 * `MutableStateFlow.update` 走 `compareAndSet`,姿态没变时 `copy(同值)` 与旧值 equals 相等,
 * 压根不发新值 —— 镜头静止时这条流是安静的,防抖照常结算。
 *
 * **崩溃最多丢 [intervalMs] 窗口**;真正兜底是切后台时那次 [flush]。
 */
class DeviceStatePersister(
    private val source: StateFlow<DeviceControlModel>,
    private val store: DeviceStateStore,
    private val intervalMs: Long = DEFAULT_SAVE_INTERVAL_MS,
) {
    private var job: Job? = null

    /** 上次结算之后状态有没有变过。跨协程读写,单写多读用 @Volatile 足够。 */
    @Volatile private var dirty = false

    /** 上次成功落盘的快照 —— 用来跳过"脏但内容其实没变"的空写。 */
    private var lastSaved: DeviceStateSnapshot? = null

    /** 上次落盘是否失败 —— 只用来抑制"持续失败刷屏",不改变重试行为。 */
    private var lastSaveFailed = false

    /** 幂等:重复调不会起第二条收集协程。 */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            source
                // StateFlow 会在订阅瞬间 replay 当前值 —— 那不是"变化",不该触发一次落盘
                // (否则全新安装也会先落一份空存档)。
                .drop(1)
                .map { it.toDeviceStateSnapshot() }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    dirty = true
                    delay(intervalMs)
                    saveNow(snapshot)
                }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    /**
     * 立即结算一次(切后台 / 冷启动恢复完时调)。返回 true 表示本次真的写了盘。
     */
    suspend fun flush(): Boolean {
        if (!dirty) return false
        return saveNow(source.value.toDeviceStateSnapshot())
    }

    /**
     * 失败时**保留脏标记**(下次变化或 [flush] 自然重试),但只在失败状态翻转时打日志 ——
     * 磁盘满 / 权限坏这类持续故障若每拍刷一条 Warning,会把日志页冲掉、反而掩盖真正的根因。
     */
    private suspend fun saveNow(snapshot: DeviceStateSnapshot): Boolean {
        if (snapshot == lastSaved) {
            dirty = false
            return false
        }
        return try {
            store.save(snapshot)
            lastSaved = snapshot
            dirty = false
            if (lastSaveFailed) {
                lastSaveFailed = false
                SystemLogger.emit(LogLevel.Info, LogTag.Resource, "设备状态落盘恢复(上次失败已重试成功)")
            }
            true
        } catch (t: Throwable) {
            if (!lastSaveFailed) {
                lastSaveFailed = true
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Resource,
                    "设备状态落盘失败(后续有变化时会重试,不再重复刷日志): " +
                        "${t::class.simpleName}: ${t.message}",
                )
            }
            false
        }
    }
}

/**
 * 落盘防抖窗口。
 *
 * 5 秒是"崩溃最多丢 5 秒演示数据"与"写盘频率"之间的折中:再小只是白写盘(没人会盯着
 * 重启前 1 秒的姿态),再大则"改完预置位立刻被杀"的概率上升。真正的兜底是切后台时那次
 * [DeviceStatePersister.flush]。
 */
internal const val DEFAULT_SAVE_INTERVAL_MS = 5_000L
