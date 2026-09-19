package com.uvp.sim.domain

/**
 * 看守位自动归位 —— GB/T 28181 里 `ResetTime` 字段的语义实现。
 *
 * 标准对 `HomePosition` 只定义三个字段,但 `ResetTime` 不是"配置数据",它是**一个行为契约**:
 * "无云台操作等待该秒数后归位到 `PresetIndex` 指向的预置位"。平台把它下发下来之后,期待发生
 * 的事情就是设备自己转回去 —— 设备不实现这一步,配置在界面上就只是个数字,演示时什么也不会
 * 发生(2026-09-16 用户要求"看守位平台+设备联动全面实现"时补上)。
 *
 * 两条路径在结构上分开了,是为了好测:
 * ```
 *   本文件:判定是否该归位(纯函数,不看时间)  ← 单测打这里
 *   SimulatorEngine:秒级节拍 + 活动信号(定时器/协程)  ← 只做"喂参数 + 落动作"
 * ```
 * 判定里刻意不碰 `Clock` —— 一旦掺进真实时间,单测就只能等真实的 N 秒。
 *
 * 注意标准**没有定义"已归位"的上报**,所以平台侧看不到这次归位,也不会因此把看守位标成
 * "已确认"。这是标准的空白,不是实现遗漏(平台侧对账比的是配置三件套,不是位姿)。
 */

/** 一次自动归位动作:回到哪个预置位、坐标是多少。 */
internal data class HomePositionReturnPlan(
    val presetIndex: Int,
    val target: PtzPose,
)

/**
 * 判定现在该不该自动归位。[idleSeconds] 由调用方按节拍累计。
 *
 * [alreadyReturned] 是"这一轮空闲里已经归过一次"的闩 —— 没有它,归位动作写回的
 * `lastCommand` 会让倒计时重新开始,于是每 `ResetTime` 秒就再归一次位,日志刷屏。
 * 任何一次新的云台操作都会把它重新打开(见 `SimulatorEngine` 的活动信号)。
 *
 * 返回值刻意不叫 `HomePositionReturn` —— `DeviceEffect` 里已经有一个同名的 effect
 * (且早已接到三个渲染端),这里给的是"该做什么"的判定结果,由调用方去发那个 effect。
 */
internal fun decideHomePositionReturn(
    model: DeviceControlModel,
    idleSeconds: Long,
    alreadyReturned: Boolean,
): HomePositionReturnPlan? {
    if (alreadyReturned) return null

    // ⛔ 巡航进行中**不做**自动归位。
    //
    // 巡航本身就是一串云台动作(见 [cruiseStepAt]),它每一步都会写 `lastCommand` 从而把空闲
    // 倒计时清零 —— 但那只在**步与步之间**成立:停留时间(dwell,默认 30 秒)期间设备确实
    // "没在动",于是 `ResetTime` 比 dwell 短时(平台允许 10 秒起)看守位会在巡航中途把镜头
    // 拽回看守位,接着下一拍巡航又把它转走 —— 两个"设备自主行为"抢同一个 pose,画面抽搐。
    // 标准里两者互不提及,没有"谁优先"的规定;按操作员的意图,他按了巡航就该一直巡,
    // 直到他停掉巡航、或 ResetTime 从**巡航停止之后**重新计时。
    if (model.activeCruiseTrack != null) return null

    if (!model.homePositionEnabled) return null

    // ResetTime 是"等多久",0 在这套语义里没有可用含义 —— 平台侧也只在 >= 10 时才允许
    // 启用(见 controllers.UpdatePTZHomePosition 的校验)。当成"不自动归位"处理,免得
    // 演成"配置刚下发就瞬间弹回去"。
    val resetSeconds = model.homePositionResetTime ?: return null
    if (resetSeconds <= 0) return null
    if (idleSeconds < resetSeconds.toLong()) return null

    val presetIndex = model.homePositionPresetIndex ?: return null
    // 预置位不存在(平台下发了一个本机还没有的编号)时不动作 —— 真实设备不会为一个不存在的
    // 预置位凭空造坐标。这条跟 PresetHandler 的处理口径一致:只落配置,归位交给"碰巧存在"。
    val target = model.presets[presetIndex] ?: return null
    return HomePositionReturnPlan(presetIndex, target)
}

/**
 * 当前云台是否正在转(六轴速率任一非 0)。
 *
 * 用来把**本机手操**也纳入"云台操作"的判据:平台下发会写 `lastCommand`,而本机方向盘/
 * 光圈聚焦长按键走的是 `adjustLocalPtzPosition`,不写 `lastCommand`。只看 `lastCommand`
 * 的话,人正在用本机方向键转云台时倒计时照走,松手前就会被硬拉回看守位。
 */
internal val DeviceControlModel.isCameraMoving: Boolean
    get() = panSpeed != 0f || tiltSpeed != 0f || zoomSpeed != 0f ||
        focusSpeed != 0f || irisSpeed != 0f
