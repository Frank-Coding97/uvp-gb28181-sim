package com.uvp.sim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 日志 Log screen — 全屏事件流,LazyColumn 自动滚到顶。
 * 是 HomeScreen 的迷你 EventListMini 的全屏版本。
 */
@Composable
fun LogScreen(state: AppUiState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) listState.animateScrollToItem(0)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("协议日志", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("最近 ${state.events.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.events) { ev ->
                    val (label, detail) = renderSimEvent(ev)
                    Column {
                        Text(label, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium)
                        if (detail.isNotEmpty()) {
                            Text(detail, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
