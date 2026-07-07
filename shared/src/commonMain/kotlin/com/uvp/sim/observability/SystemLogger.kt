package com.uvp.sim.observability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 系统日志全局入口。
 *
 * 设计要点:
 * - emit 非阻塞 — 业务代码任意线程调用,trySend 投递到 Channel 就返回
 * - 单消费者 actor 串行处理 — seq / sanitize / buffer 写入 / flow 广播都在协程内做,
 *   消除并发竞争,common KMP 不需要原子计数器
 * - Channel 容量 256 + DROP_OLDEST — 极端突发(协议栈风暴)时丢最旧,优于阻塞业务线程
 * - SystemLogBuffer(1000)做总量上限,UI 拉 snapshot 拿全量历史
 * - SharedFlow 给 UI 监听增量,replay=0 避免新订阅者误以为 "刚 emit" 的旧事件
 *
 * 平台壳启动时调用 [bindScope] 绑定一个长生命周期 scope(MainActivity 的 lifecycleScope
 * 或 iOS 的 GlobalScope),解绑时(很罕见)调用 [resetForTest]。
 */
object SystemLogger {

    private const val BUFFER_CAPACITY = 1000
    private const val CHANNEL_CAPACITY = 256


    private var buffer = SystemLogBuffer(BUFFER_CAPACITY)
    private var seq: Long = 0
    private var channel: Channel<Command> = newChannel()
    private val _flow = MutableSharedFlow<SystemLog>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var actorJob: Job? = null
    private var mirrorSink: ((SystemLog) -> Unit)? = null

    private var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    val flow: SharedFlow<SystemLog> get() = _flow.asSharedFlow()
    val snapshot: List<SystemLog> get() = buffer.snapshot()

    private fun newChannel() = Channel<Command>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 绑定到平台 scope。重复绑定会取消旧 actor 启新的。 */
    fun bindScope(scope: CoroutineScope) {
        actorJob?.cancel()
        actorJob = scope.launch {
            for (cmd in channel) {
                when (cmd) {
                    is Command.Emit -> {
                        seq += 1
                        val log = SystemLog(
                            seq = seq,
                            timestampMs = clock(),
                            sessionId = SessionTracker.currentId,
                            level = cmd.level,
                            tag = cmd.tag,
                            message = sanitize(cmd.message),
                            detail = cmd.detail?.let(::sanitize),
                            category = cmd.category,
                        )
                        buffer.add(log)
                        runCatching { mirrorSink?.invoke(log) }
                        _flow.emit(log)
                    }
                    Command.Clear -> {
                        buffer = SystemLogBuffer(BUFFER_CAPACITY)
                        seq += 1
                        val marker = SystemLog(
                            seq = seq,
                            timestampMs = clock(),
                            sessionId = SessionTracker.currentId,
                            level = LogLevel.Info,
                            tag = LogTag.User,
                            message = "日志已清除",
                            detail = null
                        )
                        buffer.add(marker)
                        runCatching { mirrorSink?.invoke(marker) }
                        _flow.emit(marker)
                    }
                }
            }
        }
    }

    /**
     * 平台侧可选镜像输出(console / os log)。
     *
     * - 不参与业务语义,失败不影响主 actor
     * - iOS 用它把系统日志镜像到 Xcode console,方便真机/模拟器排障
     */
    fun setMirrorSink(sink: ((SystemLog) -> Unit)?) {
        mirrorSink = sink
    }

    /** 业务侧 emit 入口 — 非阻塞、非 suspend、可在任意线程调用。
     *
     * [category] 可选 — Error/Warning 级别带上可让结构化日志查询更高效;
     * Info/Debug 级别 emit 时不强制(默认 null)。老调用点(2/3/4 参 message+detail)继续工作。
     */
    fun emit(
        level: LogLevel,
        tag: LogTag,
        message: String,
        detail: String? = null,
        category: ErrorCategory? = null,
    ) {
        channel.trySend(Command.Emit(level, tag, message, detail, category))
    }

    /**
     * 清空缓冲区。actor 串行处理 — 排队 Clear 命令,actor 收到后重建 buffer 并 emit
     * 一条「日志已清除」标记给 flow,UI 拉 [snapshot] 即可看到清空后的状态。
     *
     * 不直接改 buffer 是因为业务线程与 actor 协程并发,直接改会撞上 add()。
     */
    fun clear() {
        channel.trySend(Command.Clear)
    }

    /** 仅供测试 — 重置 buffer / seq / channel 到初始状态。 */
    internal fun resetForTest() {
        actorJob?.cancel()
        actorJob = null
        runCatching { channel.close() }
        channel = newChannel()
        buffer = SystemLogBuffer(BUFFER_CAPACITY)
        seq = 0
        mirrorSink = null
    }

    /**
     * 仅供测试 — 在测试 lambda 末尾调,关闭 channel 让 actor 协程退出 for-loop,
     * 避免 runTest 等子协程导致 UncompletedCoroutinesError。
     */
    internal fun shutdownForTest() {
        runCatching { channel.close() }
    }

    /** 屏蔽密码/令牌字段(spec §6 隐私):password=xxx → password=****。
     *
     * M-7 (audit §3):额外脱敏 SIP `Authorization:` / `Proxy-Authorization:` 等
     * 多 token 头,把整个 header 值收敛成 `<redacted>` 输出,避免日志 dump 时
     * Digest username / nonce / response 三件套泄露被字典攻击。
     *
     * P2-7:委托给 [SipHeaderRedactor] — 单一真相来源,UI 复制路径也用同一套逻辑。
     */
    internal fun sanitize(s: String): String = SipHeaderRedactor.redact(s)

    private sealed class Command {
        data class Emit(
            val level: LogLevel,
            val tag: LogTag,
            val message: String,
            val detail: String?,
            val category: ErrorCategory?,
        ) : Command()

        object Clear : Command()
    }
}
