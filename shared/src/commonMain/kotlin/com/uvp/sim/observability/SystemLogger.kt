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

    private val redactRegex = Regex(
        "(password|secret|token|authorization)\\s*[=:]\\s*\\S+",
        RegexOption.IGNORE_CASE
    )

    private var buffer = SystemLogBuffer(BUFFER_CAPACITY)
    private var seq: Long = 0
    private var channel: Channel<Pending> = newChannel()
    private val _flow = MutableSharedFlow<SystemLog>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var actorJob: Job? = null

    private var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    val flow: SharedFlow<SystemLog> get() = _flow.asSharedFlow()
    val snapshot: List<SystemLog> get() = buffer.snapshot()

    private fun newChannel() = Channel<Pending>(
        capacity = CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 绑定到平台 scope。重复绑定会取消旧 actor 启新的。 */
    fun bindScope(scope: CoroutineScope) {
        actorJob?.cancel()
        actorJob = scope.launch {
            for (p in channel) {
                seq += 1
                val log = SystemLog(
                    seq = seq,
                    timestampMs = clock(),
                    sessionId = SessionTracker.currentId,
                    level = p.level,
                    tag = p.tag,
                    message = sanitize(p.message),
                    detail = p.detail?.let(::sanitize)
                )
                buffer.add(log)
                _flow.emit(log)
            }
        }
    }

    /** 业务侧 emit 入口 — 非阻塞、非 suspend、可在任意线程调用。 */
    fun emit(level: LogLevel, tag: LogTag, message: String, detail: String? = null) {
        channel.trySend(Pending(level, tag, message, detail))
    }

    /** 仅供测试 — 重置 buffer / seq / channel 到初始状态。 */
    internal fun resetForTest() {
        actorJob?.cancel()
        actorJob = null
        runCatching { channel.close() }
        channel = newChannel()
        buffer = SystemLogBuffer(BUFFER_CAPACITY)
        seq = 0
    }

    /**
     * 仅供测试 — 在测试 lambda 末尾调,关闭 channel 让 actor 协程退出 for-loop,
     * 避免 runTest 等子协程导致 UncompletedCoroutinesError。
     */
    internal fun shutdownForTest() {
        runCatching { channel.close() }
    }

    /** 屏蔽密码/令牌字段(spec §6 隐私):password=xxx → password=****。 */
    private fun sanitize(s: String): String =
        s.replace(redactRegex) { match ->
            "${match.groupValues[1].lowercase()}=****"
        }

    private data class Pending(
        val level: LogLevel,
        val tag: LogTag,
        val message: String,
        val detail: String?
    )
}
