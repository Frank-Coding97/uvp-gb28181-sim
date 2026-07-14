package com.uvp.sim.domain.location

import com.uvp.sim.config.GeoPoint

/**
 * 位置数据源抽象 — 生产端 Android/iOS 有独立 impl,commonTest 用 [com.uvp.sim.domain.MockGpsSource]。
 *
 * 参见 [[wiki/projects/uvp-gb28181-sim/plans/real-gps-source]] §3.1。
 */
interface LocationProvider {
    /**
     * 启动位置监听。幂等 — 已启动时是 no-op。
     * Android impl:注册 LocationManager GPS + NETWORK updates;权限缺失 / provider disabled
     * 立即返回(不抛),latestFix 保持 null。
     */
    fun start()

    /** 停止位置监听。幂等。释放系统资源,latestFix 清 null。 */
    fun stop()

    /**
     * 返回当前最新 fix。语义 = "此刻的最佳可用位置数据"。
     *
     * null 语义(4 种情况全部收敛到同一信号,消费方一律 skip):
     *   1. 生产 impl 从未 start()
     *   2. start() 时权限缺失(ACCESS_FINE_LOCATION 未授权)
     *   3. 系统定位服务全局关闭 / provider disabled 回调触发
     *   4. 已 start() 但尚未收到首帧 fix
     *
     * non-null:
     *   · speed 单位 m/s(与 Android Location.getSpeed / iOS CLLocation.speed 原生一致 —
     *     协议层 km/h 换算在 MobilePositionNotify.build 里做)
     *   · direction 单位 度(0-360,正北 0 顺时针)
     *   · altitude 米(海拔)
     *   · 坐标系 WGS-84(Android LocationManager 原生 WGS-84,不做坐标转换)
     */
    fun next(): PositionFix?
}

/**
 * 消费方防漏 null 惯用扩展 — 所有 sendPositionNotify / sendMobilePositionResponse
 * 类路径必须通过这个扩展消费,便于 grep 审计。
 */
inline fun LocationProvider.nextOrSkip(block: (PositionFix) -> Unit) {
    next()?.let(block)
}

/**
 * 一次位置定位快照。单位契约见 [LocationProvider.next] KDoc。
 *
 * @property accuracy 定位精度(米),仅用于 Android 内部 GPS/NETWORK 精度择优,不上报 XML
 * @property fixTimeMs 采集时间戳(epoch ms),给 <Time> 字段用(秒截断)
 */
data class PositionFix(
    val point: GeoPoint,
    val speed: Double,
    val direction: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val fixTimeMs: Long = 0L,
)
