package com.uvp.sim.domain.location

import com.uvp.sim.config.GeoPoint
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 系统 CoreLocation 实现的真实定位 [LocationProvider](plan §5.1 iOS 版本)。
 *
 * · 权限:`NSLocationWhenInUseUsageDescription`(Info.plist);未授权时 [start] 触发系统请求,latestFix 保持 null 直到用户同意
 * · Accuracy:`kCLLocationAccuracyBest`(约 5-10 m 户外),GPS + WiFi + 基站融合
 * · 精度择优:CLLocationManager 内部融合虽已很好,delegate 回调按 [CLLocation.horizontalAccuracy] 比较防止 batch 陈旧 fix 覆盖高精度点
 * · Speed 单位:透传 m/s(与 [CLLocation.speed] 原生一致 — 无效时 -1 归 0),km/h 换算在 builder 层做
 * · 坐标系:CLLocation 原生 WGS-84,不做转换
 *
 * 线程模型:CLLocationManager delegate 回调跟 manager 的创建线程一致(engineScope
 * 主线程),[next] 也是同线程读,`@Volatile` 足够,不用 AtomicReference。
 */
@OptIn(ExperimentalForeignApi::class)
class IosCoreLocationProvider : LocationProvider {

    @Volatile private var latestFix: PositionFix? = null
    @Volatile private var started = false

    // CLLocationManager / delegate 必须持强引用直到 stop
    private var manager: CLLocationManager? = null
    private var delegate: LocationDelegate? = null

    /**
     * plan §5.1 iOS — start 必须在主线程执行:CLLocationManager 要求 delegate 回调
     * 派发到"创建 manager 的线程且该线程带 run loop"(Apple 文档硬性要求),否则
     * didUpdateLocations 静默丢失。生产调用点(ManscdpRouterImpl → engineScope)
     * 不保证主线程,这里显式 dispatch_async 到主队列。
     *
     * 同步 no-op 短路 [started] 先在调用线程判 —— 幂等契约不能被 async 破坏。
     */
    override fun start() {
        if (started) return
        started = true // 抢先标记,避免连续 start 重复入队
        runOnMainThread {
            if (!started) return@runOnMainThread // stop() 已被调,直接放弃
            if (manager != null) return@runOnMainThread // 已经建过 manager,幂等短路
            val mgr = CLLocationManager()
            mgr.desiredAccuracy = kCLLocationAccuracyBest
            mgr.distanceFilter = MIN_DISTANCE_M
            val dg = LocationDelegate(
                isStartedRef = { started && manager != null },
                onUpdate = { fix ->
                    // cross-review R1 #2 修复 — 参照 AndroidSystemLocationProvider 同款策略:
                    //   · 严格 accuracy 择优会永久冻结坐标(移动场景第一次高精度点覆盖后续)
                    //   · 改成"新鲜度优先 + 同窗口 accuracy 择优 + 精度容忍"
                    val current = latestFix
                    val stale = current == null ||
                        (fix.fixTimeMs - current.fixTimeMs) > ACCURACY_WINDOW_MS
                    val accuracyOk = current == null ||
                        fix.accuracy <= current.accuracy * ACCURACY_TOLERANCE_FACTOR
                    if (stale || accuracyOk) {
                        latestFix = fix
                    }
                },
                onAuthDenied = { latestFix = null },
            )
            mgr.delegate = dg

            when (CLLocationManager.authorizationStatus()) {
                kCLAuthorizationStatusNotDetermined -> mgr.requestWhenInUseAuthorization()
                kCLAuthorizationStatusAuthorizedAlways,
                kCLAuthorizationStatusAuthorizedWhenInUse -> mgr.startUpdatingLocation()
                kCLAuthorizationStatusDenied,
                kCLAuthorizationStatusRestricted -> Unit // 拒 / 受限,latestFix 保持 null(spec AC-6)
                else -> Unit
            }

            manager = mgr
            delegate = dg
        }
    }

    override fun stop() {
        if (!started) return
        started = false // 抢先标记,让 delegate 回调看到 dead 状态放弃 startUpdating
        latestFix = null // 立刻清 latestFix,不用等主队列执行
        runOnMainThread {
            manager?.stopUpdatingLocation()
            manager?.delegate = null
            manager = null
            delegate = null
        }
    }

    override fun next(): PositionFix? {
        val fix = latestFix ?: return null
        // cross-review R1 #2 修复 — 最大 fix 年龄校验(iOS CLLocation 可能返回缓存位置,不能当"当前"上报)
        // fixTimeMs <= 0 视作未知,不校验(测试 fixture 兼容)
        if (fix.fixTimeMs > 0L) {
            val nowMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
            val ageMs = nowMs - fix.fixTimeMs
            if (ageMs > MAX_FIX_AGE_MS) return null
        }
        return fix
    }

    /** 主线程执行 — 已在主线程就直接同步跑,否则 dispatch_async 到主队列。 */
    private inline fun runOnMainThread(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    /**
     * CLLocationManagerDelegate — 只处理 didUpdateLocations / didChangeAuthorization。
     * 权限运行时被 revoke:didChangeAuthorization → 拒了就 stopUpdatingLocation +
     * 清 latestFix,行为跟 Android SecurityException 分支一致(spec AC-6)。
     */
    /**
     * CLLocationManagerDelegate — 处理 didUpdateLocations / didChangeAuthorization。
     *
     * [isStartedRef] 是回外层 provider 的存活探针 —— [stop] 后 provider.started = false,
     * 授权回调(可能在弹窗流程中被 iOS 触发多次)不会误重启 stopUpdatingLocation(P0-2 fix)。
     */
    private class LocationDelegate(
        private val isStartedRef: () -> Boolean,
        private val onUpdate: (PositionFix) -> Unit,
        private val onAuthDenied: () -> Unit,
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            if (!isStartedRef()) return
            val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            onUpdate(loc.toPositionFix())
        }

        @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // 定位失败(GPS 无信号 / 用户拒等)—— 静默(spec AC-6 合规默认)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (!isStartedRef()) return // stop() 后不再响应授权变化(P0-2 fix)
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusAuthorizedAlways,
                kCLAuthorizationStatusAuthorizedWhenInUse -> manager.startUpdatingLocation()
                kCLAuthorizationStatusDenied,
                kCLAuthorizationStatusRestricted -> {
                    manager.stopUpdatingLocation()
                    onAuthDenied()
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val MIN_DISTANCE_M = 2.0
        // cross-review R1 #2 修复常量(与 AndroidSystemLocationProvider 对齐):
        const val ACCURACY_WINDOW_MS = 5_000L
        const val ACCURACY_TOLERANCE_FACTOR = 1.5f
        const val MAX_FIX_AGE_MS = 30_000L
    }
}

/** [CLLocation] → [PositionFix] 换算(WGS-84 直接透传,speed 保持 m/s)。 */
@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toPositionFix(): PositionFix {
    val coord = coordinate.useContents { latitude to longitude }
    val speedMps = if (speed >= 0.0) speed else 0.0
    val courseDeg = if (course >= 0.0) course else 0.0
    val acc = if (horizontalAccuracy >= 0.0) horizontalAccuracy.toFloat() else Float.MAX_VALUE
    // CLLocation.timestamp 是 NSDate,timeIntervalSince1970 是它的属性(秒,double)
    val fixTimeMs = (timestamp.timeIntervalSince1970 * 1000.0).toLong()
    return PositionFix(
        point = GeoPoint(longitude = coord.second, latitude = coord.first),
        speed = speedMps,
        direction = courseDeg,
        altitude = altitude,
        accuracy = acc,
        fixTimeMs = fixTimeMs,
    )
}
