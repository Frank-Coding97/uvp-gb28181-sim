package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState

/**
 * Stateless app composable. Render-only — all state and event handling are
 * passed in by the platform-specific shell (MainActivity on Android,
 * UIViewController on iOS).
 *
 * UI structure:
 *   - status pill (color reflects [SipState])
 *   - server / device summary
 *   - Connect / Disconnect buttons
 *   - scrolling event log (most recent first)
 */
@Composable
fun App(
    state: SipState,
    serverLabel: String,
    deviceLabel: String,
    events: List<SimEvent>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                StatusPill(state)
                Spacer(Modifier.height(12.dp))
                InfoCard(serverLabel = serverLabel, deviceLabel = deviceLabel)
                Spacer(Modifier.height(16.dp))
                ButtonRow(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect
                )
                Spacer(Modifier.height(16.dp))
                Text("Event log", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                EventLog(events)
            }
        }
    }
}

@Composable
private fun StatusPill(state: SipState) {
    val color = when (state) {
        SipState.Disconnected -> Color(0xFF9E9E9E)
        SipState.Registering -> Color(0xFFFFA000)
        SipState.Registered -> Color(0xFF2E7D32)
        SipState.InCall -> Color(0xFF1565C0)
        SipState.Failed -> Color(0xFFC62828)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = state.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoCard(serverLabel: String, deviceLabel: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Server", style = MaterialTheme.typography.labelSmall)
            Text(serverLabel, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Device ID", style = MaterialTheme.typography.labelSmall)
            Text(deviceLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ButtonRow(
    state: SipState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val canConnect = state == SipState.Disconnected || state == SipState.Failed
        val canDisconnect = state == SipState.Registered || state == SipState.Registering ||
            state == SipState.InCall
        Button(
            onClick = onConnect,
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(0.5f)
        ) { Text("Connect") }
        OutlinedButton(
            onClick = onDisconnect,
            enabled = canDisconnect,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors()
        ) { Text("Disconnect") }
    }
}

@Composable
private fun EventLog(events: List<SimEvent>) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(events) { e -> EventRow(e) }
    }
}

@Composable
private fun EventRow(e: SimEvent) {
    val (label, detail) = renderEvent(e)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun renderEvent(e: SimEvent): Pair<String, String> = when (e) {
    is SimEvent.RegistrationStarted -> "REGISTER →" to e.server
    is SimEvent.RegistrationChallenged -> "401 challenge" to e.nonce.take(80)
    is SimEvent.RegistrationSucceeded -> "✓ Registered" to "expires=${e.expiresSeconds}s"
    is SimEvent.RegistrationFailed -> "✗ Register failed" to e.reason
    is SimEvent.HeartbeatSent -> "♥ Keepalive #${e.sequence}" to ""
    is SimEvent.HeartbeatAcknowledged -> "♥ ack #${e.sequence}" to ""
    is SimEvent.IncomingInvite -> "INVITE ←" to e.callId
    is SimEvent.CallEnded -> "Call ended" to "${e.callId} (${e.reason})"
    is SimEvent.MessageSent -> "TX ${describeMsg(e.message)}" to ""
    is SimEvent.MessageReceived -> "RX ${describeMsg(e.message)}" to ""
    is SimEvent.TransportError -> "⚠ Transport error" to e.description
}

private fun describeMsg(m: SipMessage): String = when (m) {
    is SipRequest -> m.method.name
    is SipResponse -> "${m.statusCode} ${m.reasonPhrase}"
}
