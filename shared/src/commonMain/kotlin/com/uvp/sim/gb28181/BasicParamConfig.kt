package com.uvp.sim.gb28181

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.SimConfig
import kotlinx.serialization.Serializable

/**
 * GB/T 28181 **A.2.1.19 基本参数配置类型**（`basicParamCfgType`）中，**平台下发过**的那部分。
 *
 * 四个字段全部 `minOccurs="0"`（可选）：`Name` / `Expiration` / `HeartBeatInterval` /
 * `HeartBeatCount`。所以这一份状态里到处是 `Int?` / `String?`，
 * `null` = **平台没下发这一项**（不是"平台下发了一个 0"）。
 *
 * ⛔⛔ **`basicParamCfgType` 里没有 `DeviceID`** —— 本仓回读应答原先把 `<DeviceID>` 塞在
 * `<BasicParam>` 里（照 2016 记忆里的老写法），那是**多了一个标准未定义的元素**：
 * XSD 校验过不了，严格的对端会判报文非法。目标设备编码的合法位置只有一个 —— 应答**顶层**的
 * `<DeviceID>`（已经发了）。（2026-09-19 逐字段核对 `appA.txt` A.2.1.19 / `appA2016.txt` A.2.3.2.2 后改正。）
 *
 * ⭐ **2026-09-19 补：2016 的*回读*比 2022 多三个字段（读/写门禁不同）。**
 *
 * | 位置 | 字段 |
 * |---|---|
 * | 2016 A.2.3.2 b) **下发** | 上面 4 个（全可选） |
 * | 2016 A.2.6 j) **回读应答** | 上面 4 个 **+ `PositionCapability` / `Longitude` / `Latitude`** |
 * | 2022 A.2.1.19（下发与回读**共用**同一类型） | 只有 4 个 —— 那 3 个被删掉了 |
 *
 * 这四个字段在 2016 下发侧仍全是 `minOccurs=0`，所以 [BasicParamState] 不必扩：
 * 另外三个是**设备能力/状态的出口**，不是平台配置的入口。它们的值来自设备本身（见
 * [BasicParamConfig.render]），与平台的写入无关 —— 所以**刻意不进这一类**。
 * 这是本项目第三次遇到「同一 ConfigType，读/写门禁不同」（另两处见 A 组配置族的 `OSDCfgType`
 * 与 `VideoParamAttribute`）。
 *
 * ⚠️ **口径边界（刻意为之）**：本模拟器对 BasicParam 只**记账**，
 * 心跳节拍、注册过期时间仍按本机 [SimConfig] 跑（`RegistrationCoordinator` / `SipRegisterBuilders`
 * 直接读 config）。所以「平台改心跳间隔 → 回读一致」证明的是**链路通了**，
 * 不是设备真的改了心跳周期。与 `VideoParamAttribute` 的记账口径一致。
 * 要让心跳真生效，得把 `RegistrationCoordinator` 的节拍改成读设备状态 —— 那是另一条链。
 */
@Serializable
data class BasicParamState(
    /** `Name`，设备名称。null = 平台未下发该项。 */
    val name: String? = null,
    /** `Expiration`，注册过期时间（秒）。null = 平台未下发该项。 */
    val expiration: Int? = null,
    /** `HeartBeatInterval`，心跳间隔时间（秒）。null = 平台未下发该项。 */
    val heartBeatInterval: Int? = null,
    /** `HeartBeatCount`，心跳超时次数。null = 平台未下发该项。 */
    val heartBeatCount: Int? = null,
) {
    /** 四项全缺席 —— 这种下发等价于"什么都没配"，不写状态也不打日志（见 `ConfigParse.Absent` 的口径）。 */
    val isEmpty: Boolean
        get() = name == null && expiration == null && heartBeatInterval == null && heartBeatCount == null
}

/**
 * 回读时**实际报出**的四个值。全部非空 —— 平台配过的取配过的，没配过的取本机值（[SimConfig]）。
 *
 * 单独立一个类型而不是复用 [BasicParamState]：那个的可空性表达的是"报文里有没有这个元素"，
 * 这个表达的是"设备现在的确定值"。混用一个类型会让调用方分不清该不该判空。
 */
data class BasicParamEffective(
    val name: String,
    val expiration: Int,
    val heartBeatInterval: Int,
    val heartBeatCount: Int,
)

/** `BasicParam` 的解析、取值合并与线格式。 */
object BasicParamConfig {

    /** 秒数类字段的合理上限（标准未规定上限，这里挡明显离谱的值，避免落库后回读出一份荒谬配置）。 */
    private const val MAX_SECONDS = 86_400

    /** 心跳超时次数的合理上限（标准未规定，同上）。 */
    private const val MAX_HEARTBEAT_COUNT = 1_000

    /** `PositionCapability` 取值（2016 A.2.6 j)）：0-不支持 / 1-支持 GPS / 2-支持北斗。 */
    const val POSITION_CAPABILITY_GPS = 1

    /**
     * 解析下发报文。
     *
     * 四个字段都可选，所以只做**在场即校验**：在场但非整数 / 越界 → 整块拒绝。
     * 四项都没下发 → [ConfigParse.Rejected]（"平台发了空的 BasicParam"没有可落库的内容，
     * 静默收下会让日志和状态都看不出这次下发到底做了什么）。
     *
     * ⛔ 2016 的 `PositionCapability` / `Longitude` / `Latitude` **不在这里解析** ——
     * 它们在 2016 只在**回读**侧出现（见 [BasicParamState] 类注释），解析了也没有落点。
     */
    fun parse(xml: String): ConfigParse<BasicParamState> {
        val body = configBlockBody(xml, DeviceConfigBlock.BasicParam.configType)
            ?: return absentOrEmptyBlock(xml, DeviceConfigBlock.BasicParam.configType)

        val expiration = optionalInt(body, "Expiration", min = 1, max = MAX_SECONDS)
        if (expiration is IntField.Rejected) return ConfigParse.Rejected(expiration.reason)
        val heartBeatInterval = optionalInt(body, "HeartBeatInterval", min = 1, max = MAX_SECONDS)
        if (heartBeatInterval is IntField.Rejected) return ConfigParse.Rejected(heartBeatInterval.reason)
        val heartBeatCount = optionalInt(body, "HeartBeatCount", min = 1, max = MAX_HEARTBEAT_COUNT)
        if (heartBeatCount is IntField.Rejected) return ConfigParse.Rejected(heartBeatCount.reason)

        val state = BasicParamState(
            name = ManscdpParser.tagValue(body, "Name")?.trim()?.takeIf { it.isNotEmpty() },
            expiration = (expiration as IntField.Value).value,
            heartBeatInterval = (heartBeatInterval as IntField.Value).value,
            heartBeatCount = (heartBeatCount as IntField.Value).value,
        )
        if (state.isEmpty) return ConfigParse.Rejected("BasicParam 四项全缺（空下发）")
        return ConfigParse.Accepted(state)
    }

    /**
     * 合并出**回读要报的**四个值：平台配过的优先，没配过的退回本机值。
     *
     * ⭐ 这就是本类型的"出厂默认" —— 它**不是编出来的常量**，而是设备此刻真实的运行参数
     * （注册过期时间 / 心跳间隔都来自 [SimConfig]，与 `SipRegisterBuilders` 用的是同一份）。
     * 所以「平台从没配过 BasicParam 时回读也有值」这条成立，且值与设备行为同源。
     */
    fun effective(config: SimConfig, override: BasicParamState?): BasicParamEffective =
        BasicParamEffective(
            name = override?.name ?: config.device.name,
            expiration = override?.expiration ?: config.expiresSeconds,
            heartBeatInterval = override?.heartBeatInterval ?: config.keepaliveIntervalSeconds,
            heartBeatCount = override?.heartBeatCount ?: config.maxKeepaliveTimeouts,
        )

    /**
     * 回读应答块。
     *
     * ⛔ 四个字段**总是全部输出**（本类型的四个元素都是可选的，但设备报的是"我现在的值"，
     * 没有"缺席"这一说）。⛔ **绝不输出 `<DeviceID>`** —— 见 [BasicParamState] 的类注释。
     *
     * ⭐ **2016 分支追加三个"设备能力/状态"字段**（A.2.6 j)）：
     *
     *  - `PositionCapability` —— **能力**，不是"此刻有没有定位"。本模拟器走系统定位
     *    （Android `LocationManager` / iOS `CoreLocation`，WGS-84 系），所以恒报
     *    [POSITION_CAPABILITY_GPS]。⛔ 别按"当前有没有拿到 fix"来报 0：那会让平台
     *    在设备尚未定位成功时**永远不去订阅位置**（`PositionCapability` 存在的意义正是
     *    让平台先知道能不能订）。
     *  - `Longitude` / `Latitude` —— 取 [SimConfig.mockPosition]，即设备的**模拟起点**。
     *    ⚠️ 口径边界：这是配置里的坐标，不是"回读那一刻的实时位置"——本 `render` 是**纯函数**
     *    （有单测），拿不到由 location provider 驱动的实时值。实时位置走
     *    `MobilePosition` 通知那条链（另一条出口）。设备**没有**定位时标准允许这两个元素缺席，
     *    但模拟器总有起点，所以恒发。
     *
     * 2022 分支**不发**这三个 —— 2022 的 `basicParamCfgType` 把它们删掉了，
     * 多发会让严格校验的 2022 对端判整条非法。
     *
     * ⛔ `gbVersion` 刻意**不给默认值**：给默认值会把「忘传 → 悄悄拿本机档兜底」变成
     * 一次静默的错误形态应答，而"对面是 2016"这一路恰好只在联调时出现。
     */
    fun render(
        effective: BasicParamEffective,
        gbVersion: GbVersion,
        position: GeoPoint,
    ): String = buildString {
        append("<BasicParam>\n")
        append("<Name>").append(effective.name).append("</Name>\n")
        append("<Expiration>").append(effective.expiration).append("</Expiration>\n")
        append("<HeartBeatInterval>").append(effective.heartBeatInterval).append("</HeartBeatInterval>\n")
        append("<HeartBeatCount>").append(effective.heartBeatCount).append("</HeartBeatCount>\n")
        if (gbVersion < GbVersion.V2022) {
            append("<PositionCapability>").append(POSITION_CAPABILITY_GPS).append("</PositionCapability>\n")
            // 经纬度格式走 MobilePositionNotify.formatDouble —— 经纬度的**单一格式化真源**。
            // 与 MobilePosition 通知、VideoUploadNotify 用同一套小数位，避免
            // "同一台设备的经纬度在三条报文里长得不一样"（跨报文对账时会被当成两个值）。
            append("<Longitude>").append(MobilePositionNotify.formatDouble(position.longitude, 6))
                .append("</Longitude>\n")
            append("<Latitude>").append(MobilePositionNotify.formatDouble(position.latitude, 6))
                .append("</Latitude>\n")
        }
        append("</BasicParam>\n")
    }

    // ---- 内部：区分「字段缺席」「字段在场且合法」「字段在场但非法」 ----

    private sealed interface IntField {
        /** 元素缺席 → `value = null`；在场且合法 → 实际值。 */
        data class Value(val value: Int?) : IntField

        /** 元素在场但非法。 */
        data class Rejected(val reason: String) : IntField
    }

    /**
     * 可选整数字段的统一解析。
     *
     * ⛔ 缺席与非法必须是**两种结果**：缺席是正常路径（这个字段本来就 minOccurs=0），
     * 非法是平台发错了、要记 warn 并拒收整块。合并成 `Int?` 之后调用方就再也分不清，
     * 只能把两种都当"没值"收下 —— 于是平台的报文错误被静默吞掉。
     */
    private fun optionalInt(body: String, tag: String, min: Int, max: Int): IntField {
        val raw = ManscdpParser.tagValue(body, tag)?.trim()
        if (raw.isNullOrEmpty()) return IntField.Value(null)
        val v = raw.toIntOrNull() ?: return IntField.Rejected("$tag=`$raw` 非法（须为整数）")
        if (v < min || v > max) return IntField.Rejected("$tag=$v 越界（须为 $min~$max）")
        return IntField.Value(v)
    }
}
