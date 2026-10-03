package com.uvp.sim.domain

import kotlin.math.sqrt

/**
 * 自动扫描执行 —— `0x89 开始扫描`(GB/T 28181 表 A.10)在**设备侧**的落点。
 *
 * 控制层只定义"开始扫描 + 组号"(字节6=00H),标准**没有定义任何执行进度的上报**:设备得自己
 * 在那对左右边界之间来回扫。平台那边只有一个"启动指令已下发"的角标,看不见设备到底动了没有 ——
 * 所以设备侧不实现这一步,界面上就完全看不出扫描发生了,跟巡航当初那个缺口一模一样
 * (见 [cruiseStepAt] 的类注释;这一类"设备自主行为只记状态、没生产者"的坑本仓踩过三次)。
 *
 * 与巡航的两处**关键差异**,别照抄:
 *
 *  1. **巡航是"跳点",扫描是"连续横扫"**。巡航每一步是 ease 到某个预置位,速度 `Speed`
 *     参与不了视觉(见 `CruiseExecution` 的说明);扫描没有目标点位,它就是一条水平线上的
 *     匀速往复 ⇒ 速度**必须真的决定转速**,否则"设扫描速度"这件事在设备上完全没有落点。
 *  2. **驱动的是速率字段,不是姿态快照**。[scanStepAt] 只算出"这一拍该朝哪边、水平速率多少",
 *     由 [SimulatorEngine] 写进 `DeviceControlModel.panSpeed` —— 渲染端(Android 逐帧积分 /
 *     iOS native)本来就在按「速率 × 时间」积分姿态,于是横扫是**逐帧平滑**的,而不是
 *     每拍跳一小格。这也正是方向命令族(表 A.5)在设备侧的既有机制,扫描复用它。
 *
 * 结构同 [cruiseStepAt] / [decideHomePositionReturn],为的是好测:
 * ```
 *   本文件:判定"这一拍该朝哪边走、多快"(纯函数,不看时间、不看时钟)  ← 单测打这里
 *   SimulatorEngine:150ms 节拍 + 起步/停止边沿 + 落速率              ← 只做"喂参数 + 落动作"
 * ```
 */

/** 横扫方向。语义就是"往那一侧的边界去"。 */
internal enum class ScanSweepDirection {
    TO_LEFT,
    TO_RIGHT;

    internal val opposite: ScanSweepDirection
        get() = if (this == TO_RIGHT) TO_LEFT else TO_RIGHT
}

/**
 * 一拍扫描决策:这一拍该以哪个方向、多快的水平速度走。
 *
 * [turnedAround] 只用于**掉头时打日志**(一条能对上画面节奏的记录),不参与驱动 ——
 * 驱动只需要 [panRateDegPerSec] 的正负号。
 */
internal data class ScanStepPlan(
    val groupNum: Int,
    val direction: ScanSweepDirection,
    val turnedAround: Boolean,
    /** 带符号的水平角速度(°/s),正 = 向右(panAngle 增大),直接喂 `Model.panSpeed`。 */
    val panRateDegPerSec: Float,
)

/** 表 A.10 注2 的 12 位速度满量程(`0x8A` 的低 8 位 + 高 4 位)。 */
internal const val MAX_SCAN_SPEED = 4095

/**
 * 满量程对应的横扫角速度(°/s)。
 *
 * ⚠️ **标准没有给扫描速度的量纲** —— 表 A.10 注2 只说"字节6 是低 8 位、字节7 高 4 位是数据",
 * 连单位都没有(不像停留时间是"秒")。真实设备上它是云台角速度,具体换算各家自定。
 * 所以这里的映射是**本仓的建模选择**,不是标准条款,目的是让演示成立:
 * 下面用平方根压缩,让平台常用的低档(几十~几百)也看得出在动 —— 平台默认档换算过来约 15°/s,
 * 扫过 60° 大约 4 秒一个来回,既明显又不至于让人看不清画面。
 *
 * ⛔ 改这个常量只影响**观感**,不影响任何协议字段:回读/回显一律用 `ScanGroupState.speed`
 * 的原值(12 位整数),别把换算后的 °/s 写到任何对外报文里。
 */
internal const val SCAN_MAX_RATE_DEG_PER_SEC = 90f

/**
 * 平台从未下发过速度时的出厂默认档。
 *
 * 与巡航族取同一个值 128(`DEFAULT_CRUISE_DWELL_SECONDS` 的邻居,见 `CruiseExecution`)——
 * 两个"平台没配过"的默认值不一致的话，演示时口述会自相矛盾。
 */
internal const val DEFAULT_SCAN_SPEED = 128

/**
 * 两个边界的水平跨度小于这个值就判定"无线可扫"(返回 null)。
 *
 * 边界重合(或设反了)时不该来回扫 —— 那会变成原地抖动。真实设备同样要求两个边界分开。
 */
internal const val MIN_SCAN_SPAN_DEGREES = 2f

/**
 * 水平行程极限(°),与渲染端的姿态钳制一致(Android 侧 `coerceIn(-180f, 180f)`)。
 *
 * 边界理论上只可能来自设备自己的姿态快照,而姿态已经被钳在 ±180 内;这里再钳一次是为了
 * **存档恢复 / 手造数据**这两种不经过渲染端的来源:一个越界的边界会让扫描永远到不了它的位置、
 * 卡在极限角度上单向空推。
 */
internal const val SCAN_PAN_LIMIT_DEGREES = 180f

/**
 * 掉头提前量(秒)。
 *
 * 设备读到的 `panAngle` 是渲染端**节流回写**的(两侧都是每 ~166ms 一次),再加上本拍 150ms 的
 * 节拍滞后,等判定到"到边界了"时真实位置已经又往前走了约 0.3 秒的行程。
 * 不加这个提前量的话每次掉头都冲出去几度再回来 —— 演示时就是"扫过头"。
 * 提前量按速率折算成角度 ⇒ 快慢档的掉头精度一致。
 */
internal const val SCAN_LOOK_AHEAD_SECONDS = 0.30f

/**
 * 12 位速度(1-4095)→ 横扫角速度(°/s,恒正)。
 *
 * `null`(平台没下发过)与 `<= 0`(手造报文)都取 [DEFAULT_SCAN_SPEED]。
 */
internal fun scanRateDegPerSec(speed: Int?): Float {
    val level = speed?.takeIf { it > 0 } ?: DEFAULT_SCAN_SPEED
    val clamped = level.coerceAtMost(MAX_SCAN_SPEED)
    return SCAN_MAX_RATE_DEG_PER_SEC * sqrt(clamped.toFloat() / MAX_SCAN_SPEED.toFloat())
}

/**
 * 判定这一拍该往哪边走。返回 `null` = **本拍不动**(把速率留在 0)。
 *
 * `direction` 是**调用方的边沿状态**(上一拍的方向),本函数在到达边界时把它翻过来返回;
 * 调用方把返回值存回去即可 —— 与 [cruiseStepAt] 的 `stepIndex` 一样,状态归调用方,
 * 函数本体不看时间、不看时钟(否则单测只能真等秒)。
 *
 * 不动的四种情形,各自有明确依据:
 *  1. 没有扫描在跑(`activeScanGroup == null`)—— 平台没启动过,或被全零帧停了;
 *  2. 平台上启动的组号本机没有(平台只发过"开始"、没设过边界)——
 *     真实设备不会凭空造一对边界出来(这条是本仓 `PresetHandler` / 巡航 / 看守位的一贯口径);
 *  3. **只有一侧边界**:缺一边就不扫 —— 单边扫描是标准里没有的行为,猜一个"从当前位置到那一侧"
 *     等于替平台编边界;
 *  4. 两侧边界水平跨度 < [MIN_SCAN_SPAN_DEGREES](含设反了)⇒ 无线可扫,原地不动而不是原地抖。
 */
internal fun scanStepAt(
    model: DeviceControlModel,
    direction: ScanSweepDirection,
): ScanStepPlan? {
    val groupNum = model.activeScanGroup ?: return null
    val group = model.scanGroups[groupNum] ?: return null
    val rawLeft = group.leftBoundary?.pan ?: return null
    val rawRight = group.rightBoundary?.pan ?: return null

    val leftPan = rawLeft.coerceIn(-SCAN_PAN_LIMIT_DEGREES, SCAN_PAN_LIMIT_DEGREES)
    val rightPan = rawRight.coerceIn(-SCAN_PAN_LIMIT_DEGREES, SCAN_PAN_LIMIT_DEGREES)
    if (rightPan - leftPan < MIN_SCAN_SPAN_DEGREES) return null

    val rate = scanRateDegPerSec(group.speed)
    val lookAhead = rate * SCAN_LOOK_AHEAD_SECONDS
    val current = model.panAngle

    val reachedBoundary = when (direction) {
        ScanSweepDirection.TO_RIGHT -> current >= rightPan - lookAhead
        ScanSweepDirection.TO_LEFT -> current <= leftPan + lookAhead
    }
    val next = if (reachedBoundary) direction.opposite else direction

    return ScanStepPlan(
        groupNum = groupNum,
        direction = next,
        turnedAround = reachedBoundary,
        panRateDegPerSec = if (next == ScanSweepDirection.TO_RIGHT) rate else -rate,
    )
}
