package com.uvp.sim.sip

/**
 * SIP registration / call state machine.
 * See plan v1 §4.1.
 *
 * Transitions are pure functions — no side effects. Side effects (sending packets,
 * starting timers) are orchestrated by [com.uvp.sim.domain.SimulatorEngine] which
 * observes state changes.
 */
enum class SipState {
    /** No transport, not registered. Initial state. */
    Disconnected,

    /** REGISTER sent, waiting for 200 OK / 401 challenge. */
    Registering,

    /** Got 200 OK; heartbeat is running. */
    Registered,

    /** Got an INVITE while Registered, currently streaming. */
    InCall,

    /** Last register attempt failed (timeout / 4xx without retry). */
    Failed
}

/**
 * 是否持有**可注销的注册会话**。
 *
 * 只有 Registering / Registered / InCall 在平台侧真的建立过(或正在建立)注册会话 ——
 * 注销报文(`Expires=0` REGISTER)才有对象。Disconnected / Failed 没有会话:发出去只会落到
 * 死链上,本机空等一个 `UNREGISTER_TIMEOUT_MS`(3s),平台那边也无会话可清。
 *
 * 本判据的用途是**给"重建会话"的路径二选一**(见 `AppEngine.updateConfig`):
 * 有会话 → `disconnect()`(优雅注销);无会话 → `cancelConnect()`(静默清场)。
 *
 * ⛔ 写成穷尽 `when` 而不是 `||` 链:将来新增状态时编译器会在这里报错,而不是静默把它
 * 归进"有会话"或"无会话"的某一边。
 * ⛔ UI 侧 `SipStateDto.hasActiveRegistration()` 是同一口径的独立实现(枚举类型不同,
 * 没法共用这一个函数),改这里必须同步改那边。
 */
fun SipState.hasActiveRegistration(): Boolean = when (this) {
    SipState.Registering, SipState.Registered, SipState.InCall -> true
    SipState.Disconnected, SipState.Failed -> false
}

/**
 * Events that can drive state transitions.
 * Sealed so the compiler can verify exhaustiveness in [SipStateMachine.transition].
 */
sealed class SipEvent {
    object RegisterRequested : SipEvent()
    object Register200Received : SipEvent()
    /** 401 challenge received — caller must follow up with auth. */
    data class Register401Received(val challenge: String) : SipEvent()
    /** Final failure (4xx other than 401, 5xx, or timeout). */
    data class RegisterFailed(val reason: String) : SipEvent()

    object UnregisterRequested : SipEvent()
    object UnregisterCompleted : SipEvent()

    object InviteReceived : SipEvent()
    object ByeReceived : SipEvent()
    object CallEnded : SipEvent()

    object NetworkLost : SipEvent()
}

/**
 * Pure state transition function.
 * Illegal events are silently ignored (returns the same state) — the engine logs.
 */
object SipStateMachine {

    fun transition(current: SipState, event: SipEvent): SipState = when (current) {
        SipState.Disconnected -> when (event) {
            SipEvent.RegisterRequested -> SipState.Registering
            else -> current
        }

        SipState.Registering -> when (event) {
            SipEvent.Register200Received -> SipState.Registered
            // 401 is *not* a terminal state; we stay in Registering and the engine
            // re-sends with auth. If after retry it fails, RegisterFailed is fired.
            is SipEvent.Register401Received -> SipState.Registering
            is SipEvent.RegisterFailed -> SipState.Failed
            SipEvent.NetworkLost -> SipState.Failed
            SipEvent.UnregisterRequested -> SipState.Disconnected
            else -> current
        }

        SipState.Registered -> when (event) {
            SipEvent.InviteReceived -> SipState.InCall
            SipEvent.UnregisterRequested -> SipState.Disconnected
            SipEvent.UnregisterCompleted -> SipState.Disconnected
            SipEvent.NetworkLost -> SipState.Failed
            else -> current
        }

        SipState.InCall -> when (event) {
            SipEvent.ByeReceived, SipEvent.CallEnded -> SipState.Registered
            SipEvent.UnregisterRequested -> SipState.Disconnected
            SipEvent.NetworkLost -> SipState.Failed
            else -> current
        }

        SipState.Failed -> when (event) {
            SipEvent.RegisterRequested -> SipState.Registering  // retry
            SipEvent.UnregisterRequested -> SipState.Disconnected
            else -> current
        }
    }
}
