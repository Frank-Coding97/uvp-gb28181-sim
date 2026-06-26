package com.uvp.sim.ui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.uvp.sim.ui.model.ResetSourceDto
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipMessageDto

data class NotificationItem(
    val id: Long,
    val timestampMs: Long,
    val title: String,
    val detail: String? = null,
)

data class NotificationState(
    val items: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
)

class NotificationCenter(private val maxItems: Int = 50) {

    private var nextId: Long = 0

    fun ingest(event: SimEventDto): NotificationItem? {
        return event.toNotification()
    }

    fun markAllRead(current: NotificationState): NotificationState {
        return current.copy(unreadCount = 0)
    }

    private fun SimEventDto.toNotification(): NotificationItem? {
        val (title, detail) = when (this) {
            is SimEventDto.IncomingInvite -> "收到 INVITE 点播" to "CallID: $callId"
            is SimEventDto.DeviceControlReceived -> "设备控制: $commandType" to detail
            is SimEventDto.SubscribeReceived -> "收到订阅: $kind" to "来自 $subscriber · 有效期 ${expiresSeconds}s"
            is SimEventDto.BroadcastReceived -> "收到语音广播" to "源 $sourceId → $targetId"
            is SimEventDto.MessageReceived -> {
                val req = message as? SipMessageDto.Request ?: return null
                val method = req.method.name
                if (method !in IGNORED_METHODS) {
                    "收到 $method" to message.callIdHeader()
                } else return null
            }
            is SimEventDto.CallEnded -> "通话结束" to "CallID: $callId · $reason"
            is SimEventDto.AlarmReset -> {
                when (val src = by) {
                    is ResetSourceDto.Remote -> "平台远程复位报警" to "来自 ${src.subscriber}"
                    else -> return null
                }
            }
            else -> return null
        }
        return NotificationItem(
            id = nextId++,
            timestampMs = timestampMs,
            title = title,
            detail = detail,
        )
    }

    private fun SipMessageDto.callIdHeader(): String? =
        headers.firstOrNull { it.name.equals("Call-ID", ignoreCase = true) }?.value

    companion object {
        private val IGNORED_METHODS = setOf("REGISTER", "ACK", "OPTIONS")
    }
}

@Composable
fun rememberNotificationState(events: List<SimEventDto>): Pair<NotificationState, () -> Unit> {
    val center = remember { NotificationCenter() }
    var state by remember { mutableStateOf(NotificationState()) }
    var lastSeenSize by remember { mutableStateOf(0) }

    LaunchedEffect(events.size) {
        if (events.size > lastSeenSize) {
            val newCount = events.size - lastSeenSize
            val newEvents = events.take(newCount)
            var updated = state
            for (event in newEvents.reversed()) {
                val item = center.ingest(event) ?: continue
                updated = NotificationState(
                    items = (listOf(item) + updated.items).take(50),
                    unreadCount = updated.unreadCount + 1,
                )
            }
            state = updated
        }
        lastSeenSize = events.size
    }

    val markAllRead: () -> Unit = {
        state = center.markAllRead(state)
    }
    return state to markAllRead
}
