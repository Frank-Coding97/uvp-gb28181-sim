package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.domain.ProtocolClock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object MobilePositionNotify {

    /**
     * 构造 MobilePosition NOTIFY XML body —— **双版本并存**(主仓 backlog F-10)。
     *
     * 两条形态的**标准依据不同**,不是「新写法 vs 老写法」,而是**结构不同的两种报文**:
     *
     * · **2016 扁平形态**(§9.11.2.3 c) + 2016 附录 A):
     *   `CmdType` / `SN` / `DeviceID` / `Time`(= **位置采集时间**) / `Longitude` / `Latitude` /
     *   `Speed` / `Direction` / `Altitude` —— 一次只报一台设备的一个点。
     * · **2022 列表形态**(§9.11.2.3 c) + **A.2.5.6**):
     *   `CmdType` / `SN` / `DeviceID`(目标设备/系统编码) / `Time`(= **上报通知时间**) /
     *   `SumNum` / `DeviceList@Num` / `Item(itemMobilePositionType)`;采集时间**下沉**到
     *   `Item/CaptureTime`。item 字段序按 A.2.1.14:
     *   `DeviceID` / `CaptureTime` / `Longitude` / `Latitude` / `Speed?` / `Direction?` /
     *   `Altitude?` / `Height?`。
     *
     * ⛔ **不得以「改 2022」为由打破 2016**:现网 2016 设备/平台仍在网,两版必须并存,
     * 由 [gbVersion] 分支决定出哪一种(调用方传 `ManscdpContext.effectiveGbVersion`,
     * 即附录 I 协商结果 `min(本机, 平台)`)。
     * [gbVersion] 刻意**不给默认值** —— 给个「默认 2016」会把「忘了传版本」变成一次
     * 静默的错误形态上报,正是 F-10 要修的那类毛病。
     *
     * ⚠️ 两形态的**根 `<DeviceID>` 语义不同**,故拆成两个参数:
     *   · 2016:根即位置来源通道([sourceChannelId]) —— 平台按它定位通道(`SourceCode`),
     *     **保持历史行为不变**。
     *   · 2022:根是「目标设备/系统编码」([rootDeviceId]),通道下沉到 `Item/DeviceID`
     *     ([sourceChannelId])。传错会让合规平台认不出订阅目标而丢弃整条 NOTIFY。
     *
     * ⚠️ 单位契约(plan §6.2 采纳)—— builder 承担协议层单位换算职责:
     *   · [speed] **输入** m/s(与 [com.uvp.sim.domain.location.PositionFix.speed] 一致),
     *     **输出** km/h(内部 × 3.6,符合 GB/T 28181 §9.3.5.2 Speed 单位定义;**两形态同**)
     *   · [direction] 度(0-360),透传
     *   · [altitude] 米,透传
     *   · [fixTimeMs] epoch ms,**秒截断**后转东八区 `Asia/Shanghai` 输出 `YYYY-MM-DDTHH:mm:ss`;
     *     传 0L 时 fallback 到 [nowTimestamp](系统当前时间)—— 兼容旧调用与 mock 场景。
     *     2016 落根 `<Time>`;2022 落 `Item/CaptureTime`。
     *   · [notifyTimeMs] **仅 2022 用**(根 `<Time>` = 上报通知时间);0L 取当前协议时钟。
     *     刻意走东八区格式化而不复用 [nowTimestamp](后者跟随系统时区)——
     *     这是新引入的字段,直接按 §7.10「标准时间为北京时间」办。
     *   · 2022 的可选 `Height`(地面高度,**不同于** [altitude] 海拔)**本仓无数据源**,
     *     故不输出 —— 不是遗漏,是「没有值就不发可选字段」。
     */
    fun build(
        /** 位置来源通道编码。2016 → 根 `<DeviceID>`;2022 → `Item/DeviceID`。 */
        sourceChannelId: String,
        sn: Int,
        point: GeoPoint,
        speed: Double,
        direction: Double,
        altitude: Double = 0.0,
        fixTimeMs: Long = 0L,
        /** 本次采用的有效版本(附录 I 协商结果)。见 KDoc 里为何刻意不给默认值。 */
        gbVersion: GbVersion,
        /**
         * 2022 列表形态报文根的 `<DeviceID>`(A.2.5.6「目标设备/系统编码」= 订阅目标设备)。
         * 2016 形态忽略此参数 —— 它的根必须固定用 [sourceChannelId],否则打破平台既有解析。
         */
        rootDeviceId: String = sourceChannelId,
        /** 2022 根 `<Time>`(上报通知时间)的 epoch ms;0L 取当前协议时钟。 */
        notifyTimeMs: Long = 0L,
    ): String {
        val lngStr = formatDouble(point.longitude, 6)
        val latStr = formatDouble(point.latitude, 6)
        val spdStr = formatDouble(speed * 3.6, 1) // m/s → km/h,GB/T 28181 §9.3.5.2
        val dirStr = formatDouble(direction, 1)
        val altStr = formatDouble(altitude, 1)
        // 采集时间两形态共用同一算法 —— 同一个 fix 在两版报文里必须是同一串字符,
        // 否则跨版本对账时会被误判成"时间不一致"。
        val captureTime = if (fixTimeMs > 0L) formatEpochMsUtc8(fixTimeMs) else nowTimestamp()
        if (gbVersion == GbVersion.V2022) {
            val notifyTime = formatEpochMsUtc8(
                if (notifyTimeMs > 0L) notifyTimeMs else ProtocolClock.now().toEpochMilliseconds(),
            )
            return """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>MobilePosition</CmdType>
<SN>$sn</SN>
<DeviceID>$rootDeviceId</DeviceID>
<Time>$notifyTime</Time>
<SumNum>1</SumNum>
<DeviceList Num="1">
<Item>
<DeviceID>$sourceChannelId</DeviceID>
<CaptureTime>$captureTime</CaptureTime>
<Longitude>$lngStr</Longitude>
<Latitude>$latStr</Latitude>
<Speed>$spdStr</Speed>
<Direction>$dirStr</Direction>
<Altitude>$altStr</Altitude>
</Item>
</DeviceList>
</Notify>
""".replace("\n", "\r\n")
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>MobilePosition</CmdType>
<SN>$sn</SN>
<DeviceID>$sourceChannelId</DeviceID>
<Time>$captureTime</Time>
<Longitude>$lngStr</Longitude>
<Latitude>$latStr</Latitude>
<Speed>$spdStr</Speed>
<Direction>$dirStr</Direction>
<Altitude>$altStr</Altitude>
</Notify>
""".replace("\n", "\r\n")
    }

    private fun nowTimestamp(): String {
        val now = ProtocolClock.now()
        val tz = TimeZone.currentSystemDefault()
        val ldt = now.toLocalDateTime(tz)
        return "${ldt.year}-${ldt.monthNumber.toString().padStart(2, '0')}-" +
            "${ldt.dayOfMonth.toString().padStart(2, '0')}T" +
            "${ldt.hour.toString().padStart(2, '0')}:" +
            "${ldt.minute.toString().padStart(2, '0')}:" +
            ldt.second.toString().padStart(2, '0')
    }

    /**
     * epoch ms → 东八区 `YYYY-MM-DDTHH:mm:ss`(秒截断,不四舍五入)。
     *
     * plan §6.1 Codex R1 P2 采纳:平台按东八区解析 `<Time>`,毫秒丢弃避免格式歧义。
     * 两形态的**采集时间**共用本函数;2022 形态的**上报通知时间**(根 `<Time>`)也走它 ——
     * §7.10 要求标准时间为北京时间,而 [nowTimestamp] 跟随系统时区,不适用于新增字段。
     */
    private fun formatEpochMsUtc8(epochMs: Long): String {
        val truncatedSec = (epochMs / 1000L) * 1000L
        val ldt = Instant.fromEpochMilliseconds(truncatedSec)
            .toLocalDateTime(TimeZone.of("Asia/Shanghai"))
        return "${ldt.year}-${ldt.monthNumber.toString().padStart(2, '0')}-" +
            "${ldt.dayOfMonth.toString().padStart(2, '0')}T" +
            "${ldt.hour.toString().padStart(2, '0')}:" +
            "${ldt.minute.toString().padStart(2, '0')}:" +
            ldt.second.toString().padStart(2, '0')
    }

    /**
     * KMP 友好的小数格式化(commonMain 没有 String.format / printf,jvm-only)。
     * 对 [decimals] 位小数 half-up 四舍五入(避开 [kotlin.math.round] 的 banker rounding)。
     *
     * 跟 [MobilePositionResponse.formatDouble] 行为一致 — 两处共用同款语义,
     * 一并维持 byte-equivalent 输出格式("%.Nf" 半进位 + 定长小数位)。
     */
    private fun formatDouble(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.toLong().toString()
        var multiplier = 1L
        repeat(decimals) { multiplier *= 10 }
        val scaled = value * multiplier
        val rounded = if (scaled >= 0) {
            kotlin.math.floor(scaled + 0.5).toLong()
        } else {
            -kotlin.math.floor(-scaled + 0.5).toLong()
        }
        val sign = if (rounded < 0) "-" else ""
        val abs = kotlin.math.abs(rounded)
        val intPart = abs / multiplier
        val fracPart = abs % multiplier
        val fracStr = fracPart.toString().padStart(decimals, '0')
        return "$sign$intPart.$fracStr"
    }
}
