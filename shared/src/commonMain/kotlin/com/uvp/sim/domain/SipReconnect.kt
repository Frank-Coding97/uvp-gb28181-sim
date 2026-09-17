package com.uvp.sim.domain

import com.uvp.sim.network.ConnectionLost
import com.uvp.sim.network.ConnectionLostReason
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 重连退避策略。
 *
 * 指数退避 + 封顶,额外交给 [ReconnectPolicy.delayMsFor]。**不加抖动**:本仓是单台设备对着
 * 一个平台的演示/联调场景,不存在"一万台设备同时掉线一起重连"的惊群问题,而抖动会让日志里
 * 的"2s 后重连"变成每次都不同 —— 排障时反而更难对照。真要上规模再补抖动。
 *
 * 次数**不封顶**:设备对上级平台的心跳/注册是"只要还活着就一直尝试"的语义(现场真机也是这样),
 * 封顶只会得到一台"平台恢复后永远醒不过来"的设备。控制代价的是 [maxDelayMs] ——
 * 退到 30 秒一次以后,代价已经可以忽略。
 */
internal data class ReconnectPolicy(
    val initialDelayMs: Long = INITIAL_DELAY_MS,
    val maxDelayMs: Long = MAX_DELAY_MS,
    val multiplier: Int = DEFAULT_MULTIPLIER,
) {
    /** 第 [attempt] 次重连前应等待的毫秒数([attempt] 从 1 起)。 */
    fun delayMsFor(attempt: Int): Long = reconnectDelayMs(
        attempt = attempt,
        initialDelayMs = initialDelayMs,
        maxDelayMs = maxDelayMs,
        multiplier = multiplier,
    )

    companion object {
        /** 1s:断链多半是平台重启/网络抖动,第一拍就要快,否则现场"看着它半天没反应"。 */
        const val INITIAL_DELAY_MS: Long = 1_000L

        /** 30s:平台长时间不可达时的稳态节奏。 */
        const val MAX_DELAY_MS: Long = 30_000L

        const val DEFAULT_MULTIPLIER: Int = 2
    }
}

/**
 * 纯函数形式的退避计算(照 [decideHomePositionReturn] / [cruiseStepAt] 的做法抽出来)。
 *
 * 抽出来的唯一理由是**可测**:退避表是"跑一晚上才看得出来的东西",真等 1+2+4+8 秒的用例
 * 既慢又会因 CI 机器慢而 flaky;纯函数下一行断言就能把整张表钉死(含封顶与溢出)。
 */
internal fun reconnectDelayMs(
    attempt: Int,
    initialDelayMs: Long,
    maxDelayMs: Long,
    multiplier: Int,
): Long {
    val start = initialDelayMs.coerceIn(1L, maxDelayMs)
    if (attempt <= 1) return start
    if (multiplier <= 1) return start
    var delay = start
    repeat(attempt - 1) {
        // 先判后乘:不然次数一大就会先溢出再封顶(负数是 coerceAtMost 拦不住的)。
        if (delay >= maxDelayMs) return maxDelayMs
        delay *= multiplier
    }
    return delay.coerceAtMost(maxDelayMs)
}

/**
 * "正在重连"的可见状态。`null` 表示当前没在重连。
 *
 * 存在的意义是**别再谎报在线**:断链后注册状态会回到 Disconnected,若主页横幅只按
 * [com.uvp.sim.sip.SipState] 渲染,操作员看到的是「未连接 · 配置已就绪 + 一个注册按钮」,
 * 而设备其实正在自己重连 —— 他多半会去点那个按钮,或者以为功能坏了。有了这个状态,
 * UI 才能说清"断开了,正在第 N 次自动重连"。
 */
data class ReconnectAttempt(
    /** 从 1 起:第几次重连尝试(本轮的,不是累计)。 */
    val attempt: Int,
    /** 本次尝试前的退避时长,UI 可显示"X s 后重试"。 */
    val delayMs: Long,
    /** 触发本轮重连的断连原因(措辞取 [ConnectionLostReason.label],别另写一套)。 */
    val reason: ConnectionLostReason,
)

/**
 * SIP 长连接自愈监管器(GB/T 28181 §5.2)。
 *
 * **它解决的具体故障**:TCP 长连接被对端 FIN / 中间设备按 idle 回收 / 本机网络切换打死之后,
 * read loop 退出、通道置空,而**没有任何人负责重开**。旧实现下设备从此永久失联 ——
 * 注册状态还停在 Registered,心跳每 60 秒发一次、每次都写进已经死掉的 socket,
 * 至少要等 `maxKeepaliveTimeouts` 拍才触发一次自动重注册,而那次重注册同样发不出去,
 * 三次退避后落到 Failed 就**彻底不动了**。平台侧只看到"设备不再心跳",两边都看不出根因。
 *
 * **职责边界**(刻意划清楚,避免和注册域的退避打架翻倍发 REGISTER):
 * - 本类只管**连接层**:断连 → 退避 → `close()` + `connect()`。
 * - 连接层恢复后调一次 [register],之后交给注册域**自己**的 3 次退避/失败语义。
 *   注册失败是另一码事(平台在、口令错/被拒),拿连接层的无限重连去兜它只会得到一个
 *   永远退避重试、日志刷屏的假活设备。
 *
 * **一次断连只跑一轮**:`recover` 跑完就回到 `collect` 等待下一次。期间若新连接又断
 * (典型:平台进程起来了但 SIP 栈还没就绪),那次事件会在 buffer 里排队,`recover` 返回后
 * 再触发一轮 —— 循环由此自然闭合,不需要在 `recover` 里再套一层 while。
 */
internal class SipReconnectSupervisor(
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /**
     * 断连后、重连**前**必须跑完的会话收尾:停掉已随连接死掉的活跃流(清 InCall 闩),
     * 作废注册会话(否则 [register] 会因状态仍是 Registered 而直接早退)。
     */
    private val onSessionLost: suspend (ConnectionLost) -> Unit,
    /** 连接层恢复后重新注册。 */
    private val register: suspend () -> Unit,
    private val emitEvent: suspend (SimEvent) -> Unit,
    /** 重连状态变化回调(`null` = 已恢复 / 未在重连)。 */
    private val onAttemptChanged: (ReconnectAttempt?) -> Unit,
) {
    private var job: Job? = null

    /** 幂等:同一 transport 上重复调不会起第二条监管协程。 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // 顺序 collect(不用 collectLatest):一次断连的恢复流程必须跑完 ——
            // 被中途取消会让"close 了但还没 connect"这种中间态永远留在那儿。
            transport.connectionLost.collect { loss -> recover(loss) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onAttemptChanged(null)
    }

    private suspend fun recover(loss: ConnectionLost) {
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Network,
            "SIP 长连接被动断开(${loss.reason.label}): ${loss.detail} → 进入自愈重连",
        )
        onSessionLost(loss)
        emitEvent(SimEvent.ConnectionLost(loss.reason, loss.detail))

        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            val delayMs = policy.delayMsFor(attempt)
            onAttemptChanged(ReconnectAttempt(attempt, delayMs, loss.reason))
            emitEvent(SimEvent.ReconnectScheduled(attempt, delayMs))
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Network,
                "第 $attempt 次重连:${delayMs}ms 后重建 TCP 连接",
            )
            // 取消在这里以 CancellationException 冒出并终结整个 recover ——
            // 用户注销/改配置时监管器被 cancel,不能把取消当"连接失败"接着重试。
            delay(delayMs)

            if (!rebuildConnection(attempt)) continue

            // 连接层已恢复 → 摘掉"正在重连"标记,交回注册域。
            onAttemptChanged(null)
            emitEvent(SimEvent.ReconnectSucceeded(attempt))
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Network,
                "第 $attempt 次重连成功,TCP 已重建 → 重新注册",
            )
            register()
            return
        }
    }

    /** 重建 TCP 连接。返回 true = 连接可用;false = 本轮失败(调用方继续退避)。 */
    private suspend fun rebuildConnection(attempt: Int): Boolean {
        // 退避期间链路可能已经被别人修好了(操作员在"正在重连"时点了注册 → AppEngine.connect),
        // 这时**绝不能**再 close + connect:在平台侧重开一次 TCP 等于重新绑定设备,刚建立的
        // 注册会话会被自己打断,要等下一次心跳才恢复在线。这就是 [SipTransport.isConnected] 的用途。
        if (transport.isConnected) {
            SystemLogger.emit(
                LogLevel.Info,
                LogTag.Network,
                "第 $attempt 次重连:链路已由其它路径重建,跳过重建",
            )
            return true
        }
        // 先把上一代的半死连接彻底放掉:不 close 的话 TcpSipTransport.connect() 会因为
        // 字段不为空而直接 return,拿回一条读不到东西的连接(详见该类的 connect 注释)。
        // close 自身失败不该阻止重连 → 只记日志,往下照跑。
        try {
            transport.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Network,
                "重连前关闭旧连接失败(忽略,继续重建): ${e::class.simpleName}: ${e.message}",
            )
        }
        return try {
            transport.connect()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Network,
                "第 $attempt 次重连失败: ${e::class.simpleName}: ${e.message}",
            )
            false
        }
    }
}
