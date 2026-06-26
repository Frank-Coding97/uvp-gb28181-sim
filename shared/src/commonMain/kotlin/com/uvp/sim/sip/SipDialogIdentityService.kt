package com.uvp.sim.sip

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * SIP Dialog identity 服务(Wave 2 PR-SN-IDENTITY,2026-06-26)。
 *
 * 把原 [com.uvp.sim.domain.SipSnPool] 的「单一 SN 池 + 单一 callId/fromTag」模型,
 * 拆成 3 类独立 dialog identity:
 *   - Register dialog:REGISTER / unregister / keepalive 心跳 / OPTIONS 应答
 *   - MessageNotify dialog:出栈 MESSAGE / NOTIFY 应答(Catalog / Alarm / DeviceStatus / 抓拍 …)
 *   - Invite dialog:INVITE / ACK / BYE / CANCEL / INFO(直播 / 回放 / 广播)
 *
 * 三类各自有独立的 cseq + sn 计数器,互不串扰;callId / fromTag 走统一随机源生成器
 * (跟 [DigestAuth] cnonce 一致 — `kotlin.random.Random.Default`,JVM/Android 底层
 *  ThreadLocalRandom / SecureRandom 派生,远比手写常量种子强)。
 *
 * `nextXxx()` 三个方法在内部 [Mutex] 保护下原子推进对应 counter,跨协程并发调用
 * 保证 cseq 唯一 + callId 唯一(单测 [SipDialogIdentityServiceTest] 验证 100 并发 +
 * 1000 callId 不撞)。
 *
 * 详见研究文档 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md。
 */
internal interface SipDialogIdentityService {
    suspend fun nextRegister(): RegisterDialogIdentity
    suspend fun nextMessageNotify(): MessageNotifyIdentity
    suspend fun nextInvite(): InviteDialogIdentity
}

/** Register dialog identity:REGISTER / unregister / keepalive 用。 */
internal data class RegisterDialogIdentity(
    val sn: Long,
    val cseq: Long,
    val callId: String,
    val fromTag: String,
)

/** MessageNotify dialog identity:出栈 MESSAGE / NOTIFY 应答用。 */
internal data class MessageNotifyIdentity(
    val sn: Long,
    val cseq: Long,
    val callId: String,
    val fromTag: String,
)

/** Invite dialog identity:INVITE / ACK / BYE / CANCEL / INFO 用。 */
internal data class InviteDialogIdentity(
    val sn: Long,
    val cseq: Long,
    val callId: String,
    val fromTag: String,
)

/**
 * [SipDialogIdentityService] 默认实现。3 套独立 counter + 统一随机源 callId/fromTag。
 *
 * Mutex 选型:跟 [com.uvp.sim.domain.coord.RegistrationCoordinatorImpl] 等既有
 * Coord 风格一致(`kotlinx.coroutines.sync.Mutex`),避免引入 atomicfu / expect-actual
 * 桥;Coord 的 [SipDialogIdentityService.nextXxx] 调用点全在 `suspend` 上下文,
 * `withLock` 顺其自然挂起。
 *
 * @param localIp 用来生成 callId 的 host 段(`<random>@<localIp>` 格式,跟 [SipHeaders.randomCallId] 一致)
 * @param random 随机源,默认 `Random.Default`(JVM/Android 底层 SecureRandom 派生);
 *               单测可注入种子 `Random(seed)` 做确定性回放
 * @param initialRegisterCseq / initialMessageNotifyCseq / initialInviteCseq
 *               起始 cseq(默认 0,第一次 `nextXxx().cseq == 1`)
 */
internal class DefaultSipDialogIdentityService(
    private val localIpProvider: () -> String,
    private val random: Random = Random.Default,
    initialRegisterCseq: Long = 0L,
    initialMessageNotifyCseq: Long = 0L,
    initialInviteCseq: Long = 0L,
) : SipDialogIdentityService {

    /** 便利构造:固定 localIp(单测用)。 */
    constructor(
        localIp: String,
        random: Random = Random.Default,
        initialRegisterCseq: Long = 0L,
        initialMessageNotifyCseq: Long = 0L,
        initialInviteCseq: Long = 0L,
    ) : this(
        localIpProvider = { localIp },
        random = random,
        initialRegisterCseq = initialRegisterCseq,
        initialMessageNotifyCseq = initialMessageNotifyCseq,
        initialInviteCseq = initialInviteCseq,
    )

    private val registerMutex = Mutex()
    private val notifyMutex = Mutex()
    private val inviteMutex = Mutex()

    private var registerCseq: Long = initialRegisterCseq
    private var registerSn: Long = 0L

    private var notifyCseq: Long = initialMessageNotifyCseq
    private var notifySn: Long = 0L

    private var inviteCseq: Long = initialInviteCseq
    private var inviteSn: Long = 0L

    override suspend fun nextRegister(): RegisterDialogIdentity = registerMutex.withLock {
        registerCseq += 1
        registerSn += 1
        RegisterDialogIdentity(
            sn = registerSn,
            cseq = registerCseq,
            callId = generateCallId(),
            fromTag = generateTag(),
        )
    }

    override suspend fun nextMessageNotify(): MessageNotifyIdentity = notifyMutex.withLock {
        notifyCseq += 1
        notifySn += 1
        MessageNotifyIdentity(
            sn = notifySn,
            cseq = notifyCseq,
            callId = generateCallId(),
            fromTag = generateTag(),
        )
    }

    override suspend fun nextInvite(): InviteDialogIdentity = inviteMutex.withLock {
        inviteCseq += 1
        inviteSn += 1
        InviteDialogIdentity(
            sn = inviteSn,
            cseq = inviteCseq,
            callId = generateCallId(),
            fromTag = generateTag(),
        )
    }

    /**
     * 16 字节 hex random + `@<localIp>` — 跟 [SipHeaders.randomCallId] 一致,
     * 但走注入的 [random](默认 SecureRandom 派生),保证测试可控 + 生产强随机。
     *
     * [localIpProvider] 是 lazy 派生(transport 没 bind 时返回 `0.0.0.0`),
     * 任何 throw / null-ish 回退 fallback,不让 callId 生成因 IP 不可用而崩。
     */
    private fun generateCallId(): String {
        val bytes = random.nextBytes(8)
        val hex = bytes.toHex()
        val ip = runCatching { localIpProvider().ifEmpty { "0.0.0.0" } }.getOrElse { "0.0.0.0" }
        return "$hex@$ip"
    }

    /** 10 字节 hex random — 跟 [SipHeaders.randomTag] 一致。 */
    private fun generateTag(): String {
        val bytes = random.nextBytes(5)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
