package com.uvp.sim.domain.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.uvp.sim.config.GeoPoint

/**
 * Android 系统 LocationManager 实现的真实定位 [LocationProvider](plan §5.1)。
 *
 * · 权限:需要 `ACCESS_FINE_LOCATION`(spec AC-1);缺失时 [start] 静默 no-op,latestFix 保持 null
 * · Provider:同时注册 GPS + NETWORK,精度择优覆写(Codex R1 P1 采纳)
 * · 更新参数:minTime=1000ms,minDistance=2f 米(避免静止时每秒无谓触发)
 * · Speed 单位:透传 m/s(与 [Location.getSpeed] 原生一致),km/h 换算在 builder 层做
 * · 坐标系:LocationManager 原生 WGS-84,不做转换
 */
class AndroidSystemLocationProvider(
    private val context: Context,
) : LocationProvider {

    @Volatile private var latestFix: PositionFix? = null
    @Volatile private var started = false
    /** 追踪最新 fix 来自哪个 provider — provider 被 disable 时清对应的 latestFix,避免陈旧 fix 阻挡其他 provider(F6 P1-1 fix)。 */
    @Volatile private var latestFixProvider: String? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // cross-review R1 #2 修复 — 从"严格 accuracy 择优"改为"新鲜度优先 + 同窗口 accuracy 择优":
            //   · 原策略:严格 loc.accuracy < current.accuracy 才覆写。设备移动时高精度 fix 一旦拿到,
            //     后续常返回相同或略差精度的 fix,坐标会永久冻结在第一次高精度点,持续上报陈旧位置。
            //   · 新策略:
            //     1. current 为 null / 陈旧(> ACCURACY_WINDOW_MS)→ 直接覆写(新鲜度优先)
            //     2. 落在同一窗口内 → 保持 accuracy 择优,避免 GPS/NETWORK 交替时低精度覆盖高精度
            //     3. loc 精度 <= current 精度 * 2 时也接受,允许移动中精度略降的连续更新
            val current = latestFix
            val stale = current == null || (loc.time - current.fixTimeMs) > ACCURACY_WINDOW_MS
            val accuracyOk = current == null ||
                loc.accuracy <= current.accuracy * ACCURACY_TOLERANCE_FACTOR
            if (stale || accuracyOk) {
                latestFix = loc.toPositionFix()
                latestFixProvider = loc.provider
            }
        }

        override fun onProviderDisabled(provider: String) {
            Log.i(TAG, "provider disabled: $provider")
            // F6 P1-1 fix — 若最新 fix 来自这个被关掉的 provider,清 latestFix 让其他 provider
            // 的新 fix 有机会写入(否则陈旧的高精度 GPS fix 会永久阻挡低精度 NETWORK fix)
            if (latestFixProvider == provider) {
                latestFix = null
                latestFixProvider = null
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "provider enabled: $provider")
        }

        // API 29 之前需要 override,29+ 默认空实现。兼容旧 API 时空实现即可
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun start() {
        if (started) return
        // F3 P1-3 fix — Android 12+ 允许用户只授 COARSE,此时不该拒绝定位工作,仅退化到 NETWORK_PROVIDER
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "start() skipped: no location permission granted (FINE or COARSE)")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.w(TAG, "start() skipped: LocationManager unavailable")
            return
        }
        // F2 P1-2 fix — 至少一个 provider 注册成功 才 started=true;两个都失败时保持 started=false 允许后续重试
        var anyRegistered = false
        if (hasFine) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, listener)
                anyRegistered = true
            } catch (e: SecurityException) {
                Log.w(TAG, "GPS_PROVIDER requestLocationUpdates SecurityException", e)
            } catch (e: IllegalArgumentException) {
                // Some devices don't have GPS_PROVIDER registered — fall through to NETWORK_PROVIDER
                Log.i(TAG, "GPS_PROVIDER unavailable: ${e.message}")
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, listener)
            anyRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "NETWORK_PROVIDER requestLocationUpdates SecurityException", e)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "NETWORK_PROVIDER unavailable: ${e.message}")
        }
        started = anyRegistered
        if (!anyRegistered) {
            Log.w(TAG, "start() no provider registered — will retry on next start()")
        }
    }

    override fun stop() {
        if (!started) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        try {
            lm?.removeUpdates(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeUpdates SecurityException", e)
        }
        started = false
        latestFix = null
        latestFixProvider = null
    }

    override fun next(): PositionFix? {
        val fix = latestFix ?: return null
        // cross-review R1 #2 修复 — 最大 fix 年龄校验,过期坐标当作 null,防止把几分钟前
        // 缓存的位置当"当前位置"上报给平台。System.currentTimeMillis() 与 Location.time 同源。
        // fixTimeMs <= 0 视作"未知采样时间"(测试 fixture / 缺失场景),不做年龄校验回退到原策略。
        if (fix.fixTimeMs > 0L) {
            val ageMs = System.currentTimeMillis() - fix.fixTimeMs
            if (ageMs > MAX_FIX_AGE_MS) return null
        }
        return fix
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toPositionFix(): PositionFix = PositionFix(
        point = GeoPoint(longitude = longitude, latitude = latitude),
        speed = speed.toDouble(),               // 保持 m/s(builder 层换算 km/h)
        direction = if (hasBearing()) bearing.toDouble() else 0.0,
        altitude = altitude,
        accuracy = accuracy,
        fixTimeMs = time,
    )

    private companion object {
        const val TAG = "LocationProvider"
        const val MIN_TIME_MS = 1000L
        const val MIN_DISTANCE_M = 2f
        // cross-review R1 #2 修复常量:
        //   · ACCURACY_WINDOW_MS 5s = 同窗口内保留 accuracy 择优,防止 GPS/NETWORK 交替时被低精度覆盖
        //   · ACCURACY_TOLERANCE_FACTOR 1.5x = 允许移动中精度略降(如从 5m 变 7m)持续更新
        //   · MAX_FIX_AGE_MS 30s = 超过这个年龄的 fix 视同 null,不上报陈旧坐标(GB28181 平台按新鲜数据处理)
        const val ACCURACY_WINDOW_MS = 5_000L
        const val ACCURACY_TOLERANCE_FACTOR = 1.5f
        const val MAX_FIX_AGE_MS = 30_000L
    }
}
