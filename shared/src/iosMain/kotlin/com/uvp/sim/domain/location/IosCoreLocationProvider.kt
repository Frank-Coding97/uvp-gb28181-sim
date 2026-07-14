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
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

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
    private var started = false

    // CLLocationManager / delegate 必须持强引用直到 stop
    private var manager: CLLocationManager? = null
    private var delegate: LocationDelegate? = null

    override fun start() {
        if (started) return
        val mgr = CLLocationManager()
        mgr.desiredAccuracy = kCLLocationAccuracyBest
        mgr.distanceFilter = MIN_DISTANCE_M
        val dg = LocationDelegate(
            onUpdate = { fix ->
                val current = latestFix
                if (current == null || fix.accuracy < current.accuracy) {
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
        started = true
    }

    override fun stop() {
        if (!started) return
        manager?.stopUpdatingLocation()
        manager?.delegate = null
        manager = null
        delegate = null
        started = false
        latestFix = null
    }

    override fun next(): PositionFix? = latestFix

    /**
     * CLLocationManagerDelegate — 只处理 didUpdateLocations / didChangeAuthorization。
     * 权限运行时被 revoke:didChangeAuthorization → 拒了就 stopUpdatingLocation +
     * 清 latestFix,行为跟 Android SecurityException 分支一致(spec AC-6)。
     */
    private class LocationDelegate(
        private val onUpdate: (PositionFix) -> Unit,
        private val onAuthDenied: () -> Unit,
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            onUpdate(loc.toPositionFix())
        }

        @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            // 定位失败(GPS 无信号 / 用户拒等)—— 静默(spec AC-6 合规默认)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
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
