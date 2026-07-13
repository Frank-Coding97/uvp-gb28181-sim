package com.uvp.sim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.ui.model.SessionMarkerDto
import com.uvp.sim.ui.model.SystemLogDto

/**
 * 系统日志 tab(spec §4 + plan §8.1 SystemLogTab):
 *
 * - 顶部:会话标识(spec Q7) + tag chip 过滤 + level 阈值下拉
 * - 列表:倒序展示,行展开看 detail
 * - 暂停跟随(spec Q5):暂停期间累积 99+,点浮条恢复跟随
 *
 * 默认 level=Info(隐藏 Debug 但保留采集,运维拉满 level 时显示)。
 *
 * 拆分:过滤栏/Chip/会话头/浮条在 [SystemLogFilters],
 * 单行渲染 + 时间格式化 + 复制格式在 [SystemLogRow]。
 */
@Composable
fun SystemLogTab(
    logs: List<SystemLogDto>,
    sessionMarker: SessionMarkerDto?,
    paused: Boolean = false,
    onPausedChange: (Boolean) -> Unit = {}
) {
    var levelThreshold by remember { mutableStateOf(LogLevel.Default) }
    var tagFilter by remember { mutableStateOf<LogTag?>(null) }
    var onlyErrors by remember { mutableStateOf(false) }
    var onlyThisSession by remember { mutableStateOf(false) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    val visible by remember(logs, levelThreshold, tagFilter, onlyErrors, onlyThisSession, sessionMarker) {
        derivedStateOf {
            val effectiveLevel = if (onlyErrors) LogLevel.Warning else levelThreshold
            logs.asSequence()
                .filter { it.level.priority >= effectiveLevel.priority }
                .filter { tagFilter == null || it.tag == tagFilter }
                .filter { !onlyThisSession || (sessionMarker != null && it.sessionId == sessionMarker.sessionId) }
                .toList()
        }
    }

    var seenSize by remember { mutableStateOf(0) }
    var pausedAccum by remember(paused) { mutableStateOf(0) }
    LaunchedEffect(visible.size) {
        if (paused) {
            pausedAccum = (visible.size - seenSize).coerceIn(0, 99)
        } else {
            seenSize = visible.size
            if (visible.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (sessionMarker != null) {
            SessionHeader(sessionMarker)
            Spacer(Modifier.height(6.dp))
        }
        FilterRow(
            tagFilter = tagFilter,
            onTagChange = { tagFilter = it },
            level = levelThreshold,
            onLevelChange = { levelThreshold = it },
            onlyErrors = onlyErrors,
            onOnlyErrorsChange = { onlyErrors = it },
            onlyThisSession = onlyThisSession,
            onOnlyThisSessionChange = { onlyThisSession = it },
            sessionAvailable = sessionMarker != null
        )
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 悬浮 tab bar 底部预留(iOS 130dp / 其他 0dp) —— 让最后一条能滚出 tab bar
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = floatingBottomBarReservedBottom
                ),
                reverseLayout = false
            ) {
                items(visible.size) { idx ->
                    val log = visible[visible.size - 1 - idx]  // 最新在顶
                    SystemLogRow(log, expanded = expandedSeq == log.seq) {
                        expandedSeq = if (expandedSeq == log.seq) null else log.seq
                    }
                }
            }
            if (paused && pausedAccum > 0) {
                PauseFloater(
                    count = pausedAccum,
                    onResume = {
                        onPausedChange(false)
                        pausedAccum = 0
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
            // 长按右上角空白处暂停 — 简化:点列表本身切换暂停 (P0 暂略,留导出后续)
        }
    }
}