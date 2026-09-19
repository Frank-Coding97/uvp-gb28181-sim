package com.uvp.sim.domain

/**
 * 巡航执行 —— `0x88 开始巡航`（GB/T 28181 表 A.8）在**设备侧**的落点。
 *
 * 控制层只定义"开始巡航 + 组号"，标准**没有定义任何执行进度的上报**：设备自己要按那条轨迹的
 * 点位链依次转过去、每个点停留 `StayTime` 秒、速度按 `Speed`。平台那边只有一个"启动指令已下发"
 * 的角标，看不见设备到底动了没有 —— 所以设备侧不实现这一步，界面上就完全看不出巡航发生了：
 * 「调用成功」但画面**纹丝不动**（2026-09-17 用户报的就是这个）。
 *
 * 跟 [decideHomePositionReturn] 同一套结构，为的是好测：
 * ```
 *   本文件:判定"第 N 步该走到哪"(纯函数,不看时间、不看时钟)  ← 单测打这里
 *   SimulatorEngine:秒级节拍 + 起步/停车的边沿判定 + 落动作     ← 只做"喂参数 + 落动作"
 * ```
 * 决策里刻意不接受任何时间参数 —— 一旦掺进真实时间，单测就只能真等 N 秒（`dwellTime` 默认
 * 30 秒，一秒都不能快进）。时序全部由引擎那一侧记账。
 *
 * ⛔ **速度 `Speed` 参与不了视觉**：本仓的预置位跳转是一个固定时长的 ease 动画
 * （`SceneKitEffectDispatcher` / `CameraGlbView` 里的 `easeToPose`），没有"按角速度转多久"的
 * 模型。真实设备上 `Speed` 是云台角速度，这里如实存下来、查询应答照原值回（那是平台与设备
 * 之间的契约），但不会改变动画快慢 —— 别为了"看起来像"去编一个假的时长。
 */

/**
 * 设备出厂默认停留秒数。
 *
 * ⭐ 查询应答（`DeviceControlSubRouter` 的 `CruiseTrackQuery` 应答）与执行**必须共用这一个值**：
 * 平台没通过 `0x87` 下发过停留时间时，应答里回的是它、设备实际按它走。各写一份的话会出现
 * 「界面显示停留 30 秒、设备每 5 秒就跳下一个点」这种查不出源头的偏差。
 */
internal const val DEFAULT_CRUISE_DWELL_SECONDS = 30

/**
 * 巡航的一个步进动作：走到点位链第 [pointIndex] 个（共 [pointCount] 个）预置位 [presetIndex]，
 * 到位后停留 [dwellSeconds] 秒。
 *
 * [pointIndex] 是**沿点位链的下标**（0 起，已经跳过本机没有的预置位），走到末尾回 0 —— 巡航的
 * 语义就是**一直转**，直到平台发停止指令。它不等于预置位编号：轨迹引用 P2/P3/P4 时下标 0 是 P2。
 *
 * 引擎自己那个"从启动到现在走了多少步"的计数器**不往这里塞**：那个只用来驱动节拍，
 * 对 UI 和日志没有意义（`第 7 步`这种数读不出"走到这条轨迹的第几个点"）。
 */
internal data class CruiseStepPlan(
    val trackNum: Int,
    val pointIndex: Int,
    val pointCount: Int,
    val presetIndex: Int,
    val target: PtzPose,
    val dwellSeconds: Int,
)

/**
 * 判定巡航走到 `stepIndex` 这一拍时该到哪个点。返回 `null` = **本拍不动**。
 *
 * `stepIndex` 是**调用方的累加计数器**（从启动算起走了多少步，只增不减、不重置），
 * 这里只拿它取模定位到点位链上；换轨/重启由调用方把计数器归零。
 * 函数本体不看时间、不看时钟 —— 节拍在 [SimulatorEngine] 那一侧。
 *
 * 不动的四种情形，各自有明确依据：
 *  1. 没有轨迹在跑（`activeCruiseTrack == null`）—— 平台没启动过，或被停止指令清了；
 *  2. 平台上启动的组号本机没有（轨迹还没配完，或已被 `0x85` 删空）—— 真实设备不会凭空造轨迹；
 *  3. 轨迹的点位链**一个本机存在的预置位都没有** —— 同 `PresetHandler` / `decideHomePositionReturn`
 *     的口径：引用不存在的预置位时**什么都不做**，绝不替平台编一个坐标出来；
 *  4. 本机有的预置位不足整条链时，缺的那些**逐个跳过**，剩下的照常按顺序走 —— 平台侧 UI 并不
 *     知道设备上哪几个预置位真的存住了（预置位 SET 可能失败），整条链因此卡死比跳过更糟。
 */
internal fun cruiseStepAt(model: DeviceControlModel, stepIndex: Int): CruiseStepPlan? {
    val trackNum = model.activeCruiseTrack ?: return null
    val track = model.cruiseTracks[trackNum] ?: return null

    val walkable = track.points.filter { model.presets.containsKey(it) }
    if (walkable.isEmpty()) return null

    // 取模而不是"到末尾就返回 null":巡航是循环的，走完最后一个点回到第一个点继续。
    // 负数是防御 —— 调用方只递增，但取模写全了就不用依赖这个前提。
    val slot = ((stepIndex % walkable.size) + walkable.size) % walkable.size
    val presetIndex = walkable[slot]
    // 停留时间 0 在这套语义里没有可用含义（且会让循环变成空转）。平台侧只允许 1-4095，
    // 这里再钳一道，防一条手造的报文把节拍打成死循环。
    val dwellSeconds = (track.dwellTime ?: DEFAULT_CRUISE_DWELL_SECONDS).coerceAtLeast(1)

    return CruiseStepPlan(
        trackNum = trackNum,
        pointIndex = slot,
        pointCount = walkable.size,
        presetIndex = presetIndex,
        target = model.presets.getValue(presetIndex),
        dwellSeconds = dwellSeconds,
    )
}
