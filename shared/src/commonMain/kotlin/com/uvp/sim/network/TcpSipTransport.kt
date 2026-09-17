package com.uvp.sim.network

import com.uvp.sim.concurrency.IoDispatcher
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipParseException
import com.uvp.sim.sip.SipParser
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * TCP-based SIP transport (RFC 3261 § 18.2.2).
 *
 * Opens a persistent TCP connection to the remote SIP server. Incoming bytes
 * are framed by detecting SIP message boundaries via Content-Length header
 * (RFC 3261 § 7.5 — the only reliable framing method for SIP over TCP).
 *
 * GB28181 § 5.2 requires both UDP and TCP support. Some platforms (especially
 * behind NAT/firewall) prefer TCP for reliability.
 *
 * **断链自愈(§5.2)**:本类只负责"发现 + 上报"—— read loop 被对端 FIN / 帧错误 / 读错误
 * 打死时发一条 [connectionLost],并把半死连接标记成不可用。**重连节奏不在这里**,
 * 因为"断了之后要不要重连、隔多久、重连完要不要重新注册"是会话层决策(见
 * `com.uvp.sim.domain.SipReconnectSupervisor`)。传输层自己闷头重连会得到一条
 * "TCP 通了但设备没注册"的连接,而平台侧看起来就是设备在线但其实收不到任何命令。
 */
class TcpSipTransport(
    private val remote: RemoteEndpoint,
    private val parentScope: CoroutineScope? = null
) : SipTransport {

    private val mutex = Mutex()
    private val sendMutex = Mutex()
    private var socket: Socket? = null
    private var selector: SelectorManager? = null

    // ⚠ 这两个字段被**三个不同的线程**读写:read loop(Dispatchers.Default)在断连时同步置空、
    // send() 在读、isConnected 可能被自愈协程读。read loop 的写路径刻意不拿 mutex(置空必须
    // 立即生效,否则上层还能往死链接里写),所以可见性只能靠 @Volatile ——
    // 少了它,自愈流程可能读到缓存里的旧值,把已经死掉的连接判成"可用"从而跳过重建。
    @Volatile private var readChannel: ByteReadChannel? = null
    @Volatile private var writeChannel: ByteWriteChannel? = null
    private var receiveJob: Job? = null

    /**
     * 连接世代号 —— 每成功建连一次 +1。
     *
     * 用来切断一类极隐蔽的竞态:read loop 被动终止时,它只能**异步**释放 fd(要走 mutex),
     * 而这个异步任务与调用方的重连流程是并发的。若前者晚于新一代 `connect()` 执行,不加保护
     * 就会把**刚建好的新连接**关掉 —— 现象是「重连看起来成功了,但立刻又断」,而且每轮重建
     * 都复现,极难从日志看出来(read loop 只在关掉之后才发现自己读的是个死 fd)。
     * 有了世代号,被动收尾只释放"自己那一代"的 fd。
     */
    private var generation: Long = 0

    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _incoming = MutableSharedFlow<SipEnvelope>(extraBufferCapacity = 64)
    override val incoming: Flow<SipEnvelope> = _incoming.asSharedFlow()

    /**
     * 被动断连流(见 [SipTransport.connectionLost])。
     *
     * `replay = 1` 是刻意的:订阅方(重连监管器)是在 `connect()` **之后**才挂上来的,
     * 若平台在建连瞬间就把我们 RST 掉,那次丢失发生在订阅之前 —— 纯 `replay = 0` 会让它
     * 掉在地上,表现为「连上就被关,但设备什么都不做」。回放一条最多只会在订阅瞬间被消费一次
     * (已订阅的收集者不会再收),而本类的实例与订阅方同生命周期,不会跨会话回放陈旧事件。
     */
    private val _connectionLost = MutableSharedFlow<ConnectionLost>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val connectionLost: Flow<ConnectionLost> = _connectionLost.asSharedFlow()

    override val localPort: Int
        get() = (socket?.localAddress as? InetSocketAddress)?.port ?: -1

    /**
     * 可用 = socket 已建立 **且** read loop 还在。
     *
     * 后半句才是关键:被动断连时 socket 字段仍然非空(异步释放),只看 socket 会把
     * "已经收不到任何东西的连接"报成可用,重连监管器于是一路 return 不再重建 ——
     * 设备就这么安静地卡在一条死链上。
     */
    override val isConnected: Boolean
        get() = socket != null && readChannel != null

    companion object {
        /**
         * H-2 (security-audit §2):TCP SIP 单条消息 body 上限 64 KiB。
         *
         * RFC 3261 § 18.1.1 建议 UDP MTU 1300,TCP 无硬上限但典型 SIP/MANSCDP /
         * SDP body 都在几 KB 内。攻击者可发 `Content-Length: 2147483647`
         * 让 [readByteArray] 一次性分配 2 GiB → OOM。这里设 64 KiB 上限。
         */
        internal const val MAX_SIP_BODY_BYTES: Int = 65_536

        /**
         * H-2:解析 `Content-Length:` 头一行,做合法性 + 上限校验。
         *
         * @return 解析后的字节数(0..MAX_SIP_BODY_BYTES)
         * @throws SipParseException 负数或超上限 → 视为攻击,关连接
         */
        internal fun parseContentLengthOrThrow(headerLine: String): Int {
            val raw = headerLine.lowercase().substringAfter(':').trim()
            // cross-review R1 #1:非数字 Content-Length 过去 `toIntOrNull() ?: 0` 静默当 0,
            // body 不被读取 → TCP SIP 流错位(下一 read 把 payload 当新 start-line)。
            // fail-closed:头存在但值非合法整数,视为攻击/损坏帧,抛异常关连接。
            val cl = raw.toIntOrNull()
                ?: throw SipParseException("Content-Length \"$raw\" is not a valid integer")
            if (cl < 0) {
                throw SipParseException("Content-Length $cl is negative")
            }
            if (cl > MAX_SIP_BODY_BYTES) {
                throw SipParseException(
                    "Content-Length $cl exceeds max $MAX_SIP_BODY_BYTES — possible DoS"
                )
            }
            return cl
        }

        /**
         * cross-review R1 #1 followup:判断一行 header 是否为 Content-Length
         * (含 SIP 紧凑头形式 `l:`,RFC 3261 §7.3.3)。
         *
         * TCP 分帧过去只硬匹配 "content-length:",漏了 `l:`,导致用紧凑头的合规
         * 消息 body 不被读取 → 流错位。抽成 internal 静态函数复用 [SipHeader.canonicalize]
         * 并便于单测。
         */
        internal fun isContentLengthHeaderLine(line: String): Boolean {
            val colon = line.indexOf(':')
            if (colon <= 0) return false
            return SipHeader.canonicalize(line.substring(0, colon).trim()) == SipHeader.CONTENT_LENGTH
        }

        /**
         * cross-review R1 #1 折叠根治(收敛版):从 TCP 分帧累积的物理 header 行检测 body 长度。
         *
         * **根因**:过去 TCP 分帧自己实现"逐行找 Content-Length",跟 [SipParser] 的
         * "折叠续行后取首个 Content-Length"是两套独立逻辑,反复在折叠/紧凑头/重复头/
         * 续行拼接等边界上漂移(每修一个 CodeX 又找到下一个)。
         *
         * **治本**:body 长度判定**唯一委托** [SipParser.contentLengthFrom](先按 RFC 3261
         * §7.3.1 折叠续行,再取首个 canonical Content-Length 跑 toIntOrNull),保证分帧读的
         * 字节数跟 parser 认的 body 长度**永远一致**,不可能再漂移。
         *
         * 分帧额外的 fail-closed 守卫(流式场景特有,parser 在内存里可"取剩余"但流不行):
         * - **缺失 Content-Length**:RFC 3261 §18.3 规定 TCP 流式传输必须带 Content-Length。
         *   parser 在内存里无 CL 会回退"取剩余字节",但流式无边界 → 那样会把后续消息的
         *   字节当本条 body 吞掉(消息走私/错位)。所以缺失即非法,抛异常关连接。
         * - header 存在但折叠后非数字(如 `Content-Length: 5` + 折叠 ` 0` → `"5 0"`):
         *   parser 会回退读剩余字节,流式无"剩余"概念 → 抛异常关连接
         * - 负数 / 超 [MAX_SIP_BODY_BYTES](DoS 上限)→ 抛异常
         *
         * @param headerLines start-line 之后、空行之前的所有物理 header 行(原样,未 trim)
         * @return body 字节数(0..MAX_SIP_BODY_BYTES)
         * @throws SipParseException Content-Length 缺失 / 非法 / 负数 / 超上限
         */
        internal fun detectContentLength(headerLines: List<String>): Int {
            // 折叠续行后判断(跟 parser 同口径)
            val folded = SipParser.foldContinuationLines(headerLines)
            val hasContentLength = folded.any { isContentLengthHeaderLine(it) }
            // 缺失 Content-Length:流式下 parser 会"取剩余字节",分帧无法对齐 → fail-closed
            if (!hasContentLength) {
                throw SipParseException("TCP-framed SIP message missing Content-Length (RFC 3261 §18.3)")
            }

            // 委托 parser 取值,保证口径一致;header 存在却取不到合法整数 → 流式 fail-closed
            val cl = SipParser.contentLengthFrom(folded)
                ?: throw SipParseException("Content-Length header present but not a valid integer (folded: $folded)")
            if (cl < 0) {
                throw SipParseException("Content-Length $cl is negative")
            }
            if (cl > MAX_SIP_BODY_BYTES) {
                throw SipParseException("Content-Length $cl exceeds max $MAX_SIP_BODY_BYTES — possible DoS")
            }
            return cl
        }

        /**
         * M-3 (audit §3) — TCP SIP socket option 配置。
         *
         * 抽成静态函数方便单测注入 stub 验证 keepAlive 被打开。
         * NAT 中间设备(家用路由 / 运营商 CGN)对 TCP idle 通常 5-30 分钟回收,
         * SIP TCP 长连接不开 keepalive 在 NAT 后会变成单边死连(本地认为连着,
         * 中间设备已断,平台再回包打不通)。
         *
         * ktor 跨平台 [SocketOptions.TCPClientSocketOptions.keepAlive] 在 JVM /
         * Native / iOS Network.framework 都生效;TCP_KEEPIDLE/INTERVAL 仅 JVM
         * Linux 内核可调(JDK 11+ JEP 350),其它平台用内核默认值(macOS 2h,
         * Linux 7200s)。SIP 平台多有应用层 keepalive 心跳(GB28181 默认 60s),
         * 内核 keepalive 主要兜底应用层心跳被卡死。
         *
         * 注:ktor 3.0.2 的 [SocketOptions.TCPClientSocketOptions] 构造 `internal`,
         * 无法在测试里直接构造,所以测试通过 [keepAliveSetting] 标志位 + lambda
         * 间接验证(单测注入 stub setter,断言被调用)。
         */
        internal const val keepAliveSetting: Boolean = true

        internal fun configureKeepAlive(setKeepAlive: (Boolean) -> Unit) {
            setKeepAlive(keepAliveSetting)
        }
    }

    override suspend fun connect(): Unit = mutex.withLock {
        // 判据是 **readChannel 而非 socket**:read loop 被动终止时会**同步**置空 readChannel
        // (让上层 send 立刻拿到明确失败),但 socket/selector 的 fd 释放是异步的。
        // 只判 socket 会让重连流程直接 return,拿回一条"半死"连接 —— connect() 报成功、
        // REGISTER 也写进去了,但**本机永远收不到任何响应**(read loop 早就不在了)。
        // 这种"重连成功但什么都不恢复"比不重连更难排查,所以这里必须把半死连接重开。
        if (socket != null && readChannel != null) return
        releaseSocketLocked()
        // M-6 (audit §3) — 配置白名单不空时,目标 IP 必须命中。
        ServerAllowList.enforce(remote.host, remote.allowList)
        // Android 主线程会触发 NetworkOnMainThreadException(ktor tcp().connect() 内部
        // 有同步 socket 检测,即便挂 suspend),切到 IO 线程跑。
        // ba7d597 已为 RTP TCP 修过同款问题,现在 SIP TCP 同步治理。
        withContext(IoDispatcher) {
            val sm = SelectorManager(Dispatchers.Default)
            val sk = try {
                aSocket(sm)
                    .tcp()
                    .connect(InetSocketAddress(remote.host, remote.port)) {
                        configureKeepAlive { keepAlive = it }
                    }
            } catch (e: Throwable) {
                sm.close()
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Network,
                    "TCP connect ${remote.host}:${remote.port} 失败: ${e::class.simpleName}: ${e.message}"
                )
                throw e
            }
            selector = sm
            socket = sk
            readChannel = sk.openReadChannel()
            writeChannel = sk.openWriteChannel(autoFlush = true)
            generation += 1
            val gen = generation
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "TCP connected → ${remote.host}:${remote.port} keepalive=on (连接#$gen)"
            )
            // 同 UdpSipTransport:接收循环含阻塞网络 IO(InetSocketAddress 反向 DNS 等),
            // 显式跑在 Dispatchers.Default,避免继承调用方(Android viewModelScope = Main)
            // 在主线程触发 NetworkOnMainThreadException 崩溃。
            receiveJob = ownedScope.launch(Dispatchers.Default) {
                val rc = readChannel ?: return@launch
                // TCP 单连接,sourceIp/Port 在 connect() 后就确定,read loop 复用 remote endpoint。
                val remoteAddr = sk.remoteAddress as? InetSocketAddress
                val sourceIp = remoteAddr?.hostname ?: remote.host
                val sourcePort = remoteAddr?.port ?: remote.port
                // 授权门(可能跑在主线程)只读地址形式缓存、自己不做 DNS —— 这里把
                // "配置侧"(remote.host + allowList)与"观测侧"(对端)都预热好,
                // 避免首个 MANSCDP 因缓存未命中被 fail-closed 丢掉。
                // primeHostForms 是 fire-and-forget,不会阻塞本接收循环。
                primeHostForms(remote.host)
                remote.allowList.forEach { primeHostForms(it) }
                primeHostForms(sourceIp)
                var shouldRelease = false
                // 被动终止的归类 + 原始文本,留给 finally 统一发信号(单点、不重复发)。
                var lost: ConnectionLost? = null
                try {
                    while (isActive) {
                        try {
                            val msg = readSipMessage(rc)
                            if (msg == null) {
                                // EOF / 对端 FIN:跟 parse 错误同款,read 已结束,transport 不再可用。
                                shouldRelease = true
                                lost = ConnectionLost(
                                    ConnectionLostReason.PeerClosed,
                                    "对端关闭连接 ${remote.host}:${remote.port}",
                                )
                                break
                            }
                            _incoming.emit(
                                SipEnvelope(
                                    message = msg,
                                    sourceIp = sourceIp,
                                    sourcePort = sourcePort,
                                    transport = TransportType.TCP,
                                )
                            )
                        } catch (e: SipParseException) {
                            // R1 #8:帧级解析错误(畸形 Content-Length / 越界 / Header 截断)
                            // 不能 continue —— 后续字节流已经无法对齐到合法帧边界,继续读
                            // 只会循环消费垃圾数据。直接 break read-loop。
                            SystemLogger.emit(
                                LogLevel.Warning, LogTag.Network,
                                "TCP SIP framing error, dropping connection: ${e.message}"
                            )
                            shouldRelease = true
                            lost = ConnectionLost(
                                ConnectionLostReason.FramingError,
                                e.message ?: "SIP 帧解析失败",
                            )
                            break
                        } catch (e: Throwable) {
                            if (!isActive) break
                            SystemLogger.emit(
                                LogLevel.Warning, LogTag.Network,
                                "TCP read error: ${e::class.simpleName}: ${e.message}"
                            )
                            shouldRelease = true
                            lost = ConnectionLost(
                                ConnectionLostReason.ReadError,
                                "${e::class.simpleName}: ${e.message}",
                            )
                            break
                        }
                    }
                } finally {
                    // cross-review R2 #5 (verify follow-up):任何"非 cancellation"终止 read loop
                    // (EOF / parse error / 其他 read 错)都必须**同步**置空 writeChannel/readChannel,
                    // 否则上层 send 在 close() 异步 mutex.withLock 完成前还能往死链接里写,UI 看不到
                    // 终结信号。socket / selector 的 fd 释放仍可异步,因为它们的 close() 进 mutex 阻塞。
                    //
                    // cancellation (shouldRelease=false) 走正常 close() 路径,不重复释放。
                    if (shouldRelease) {
                        writeChannel = null
                        readChannel = null
                        // 先发断连信号、再异步放 fd:上层(重连监管器)要尽快开始退避计时,
                        // 而 fd 释放要走 mutex,晚一点无所谓。
                        // tryEmit:GOP 已满时丢最旧一条 —— 保留"最新一次断连"正是我们要的语义,
                        // 这里绝不能为了投递一条信号把 read loop 的收尾卡住。
                        lost?.let { _connectionLost.tryEmit(it) }
                        // 跨代保护见 [generation]:这个 launch 与重连流程并发,晚一步执行时
                        // 若无条件释放,就会顺手关掉重连**刚建好**的那条连接。
                        ownedScope.launch { runCatching { releaseIfGeneration(gen) } }
                    }
                }
            }
        }
    }

    override suspend fun send(message: SipMessage) {
        val payload = message.toBytes()
        // write 在主线程也会撞 NetworkOnMainThreadException,统一在 IO 跑。
        //
        // cross-review R2 #2:多协程并发(SipOutboxImpl 跨多个 Coordinator)同时进 send
        // 会让两条 SIP 报文在 ByteWriteChannel 上交错,平台拿到的字节流是损坏帧。
        // sendMutex 串行整段 write,保证一条 SIP 写完再写下一条。
        //
        // R2 #2 同时治本 R1 #5 verify-followup 残留:writeChannel 的读取放在 mutex 内,
        // read-loop 在 finally 同步置空时,正在等锁的 send 拿到的就是 null,直接抛
        // "Transport not connected"。
        sendMutex.withLock {
            val wc = writeChannel ?: error("Transport not connected — call connect() first")
            withContext(IoDispatcher) {
                try {
                    wc.writeByteArray(payload)
                } catch (e: Throwable) {
                    SystemLogger.emit(
                        LogLevel.Error, LogTag.Network,
                        "TCP send 失败: ${e::class.simpleName}: ${e.message}"
                    )
                    throw e
                }
            }
        }
    }

    override suspend fun close(): Unit = mutex.withLock {
        receiveJob?.cancel()
        receiveJob = null
        readChannel = null
        writeChannel = null
        releaseSocketLocked()
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }

    /**
     * 释放当前 socket / selector 的 fd 并置空字段。**调用方必须已持有 [mutex]**。幂等。
     *
     * 抽出来是因为有两条释放路径:主动 [close] 与被动的 [releaseIfGeneration] —— 后者的跨代
     * 判断只能夹在锁内,所以"判断"和"释放"不能再各写一份。
     */
    private suspend fun releaseSocketLocked() {
        // socket.close() / selector.close() 都涉及 fd 释放,在主线程上 strict
        // mode 同样会发火,统一到 IO。
        withContext(IoDispatcher) {
            socket?.close()
            socket = null
            selector?.close()
            selector = null
        }
    }

    /**
     * read loop 被动终止时的收尾:只释放 [gen] 那一代的 fd。
     *
     * 若期间连接已被重连流程替换(世代号变了),**什么都不做** —— 否则会把新连接关掉,
     * 现象见 [generation] 的注释。
     *
     * 注意这里**不** cancel `ownedScope`(与 [close] 不同):一次被动断连只意味着"这条连接没了",
     * 不意味着"这个 transport 退休了" —— 它还要被重连复用。真正退休时由 [close] 负责。
     */
    private suspend fun releaseIfGeneration(gen: Long) {
        mutex.withLock {
            if (gen != generation) return@withLock
            releaseSocketLocked()
        }
    }

    /**
     * Read a single SIP message from TCP stream using Content-Length framing.
     *
     * SIP over TCP: read headers line-by-line until blank line (CRLFCRLF),
     * extract Content-Length, then read exactly that many body bytes.
     *
     * H-2:Content-Length 上限 [MAX_SIP_BODY_BYTES],超过则抛 [SipParseException]
     * 触发外层 break(连接关闭),避免攻击者用畸形 CL 触发 OOM。
     */
    private suspend fun readSipMessage(channel: ByteReadChannel): SipMessage? {
        val headerLines = mutableListOf<String>()

        while (true) {
            val line = channel.readUTF8Line() ?: return null
            if (line.isEmpty()) break
            headerLines.add(line)
        }

        if (headerLines.isEmpty()) return null

        // cross-review R1 #1 折叠根治:累积完所有 header 行后统一检测 Content-Length,
        // detectContentLength 跟 SipParser 一样跳过 RFC 3261 §7.3.1 折叠续行,
        // 避免续行 ` l: 5` 被误当真 Content-Length 头致分帧错位。
        val contentLength = detectContentLength(headerLines)

        val headerBytes = headerLines.joinToString("\r\n").encodeToByteArray()
        val separator = "\r\n\r\n".encodeToByteArray()

        val bodyBytes = if (contentLength > 0) {
            channel.readByteArray(contentLength)
        } else {
            ByteArray(0)
        }

        val full = headerBytes + separator + bodyBytes
        return SipParser.parse(full)
    }
}
