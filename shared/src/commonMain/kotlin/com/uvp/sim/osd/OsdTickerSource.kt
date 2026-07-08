package com.uvp.sim.osd

import com.uvp.sim.config.OsdConfig
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * OSD 三层运行期文本源 — GL thread 每帧调 [snapshot] 拿当前应渲染文本。
 *
 * - timestamp 层文本由 [clock] 实时格式化(YYYY-MM-DD HH:MM:SS.mmm)
 * - channelName / watermark 层文本来自 [config].value 的对应字段
 * - 任何层 enabled=false 或 text 空字符串 → 对应快照字段返回 null(不渲染)
 *
 * [config] 是热源,UI 改 OsdConfig → 立刻反映到下一帧 snapshot,
 * 不需要 ticker 自己重订阅(GL thread 主动 pull)。
 *
 * [clock] 默认 [Clock.System],测试可注入 fixed clock 验证格式。
 * [timeZone] 默认 [TimeZone.currentSystemDefault],设备时区跟随系统。
 */
class OsdTickerSource(
    private val config: StateFlow<OsdConfig>,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {
    fun snapshot(): OsdSnapshot {
        val cfg = config.value
        return OsdSnapshot(
            timestamp = if (cfg.timestamp.enabled) formatNow() else null,
            channelName = cfg.channelName.text.takeIf { cfg.channelName.enabled && it.isNotEmpty() },
            watermark = cfg.watermark.text.takeIf { cfg.watermark.enabled && it.isNotEmpty() }
        )
    }

    private fun formatNow(): String {
        val instant = clock.now()
        val ldt = instant.toLocalDateTime(timeZone)
        val millis = (instant.toEpochMilliseconds() % 1000).let { if (it < 0) it + 1000 else it }
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append(' ')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
            append('.')
            append(millis.toString().padStart(3, '0'))
        }
    }
}

/**
 * 一帧 OSD 文本快照 — 任一字段为 null 表示该层本帧不渲染。
 */
data class OsdSnapshot(
    val timestamp: String?,
    val channelName: String?,
    val watermark: String?
)
