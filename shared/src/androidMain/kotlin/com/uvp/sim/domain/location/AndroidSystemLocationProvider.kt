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
            // 精度择优:新 fix accuracy 更好 才覆写,避免 GPS 高精度被 NETWORK 低精度盖掉
            val current = latestFix
            if (current == null || loc.accuracy < current.accuracy) {
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

    override fun next(): PositionFix? = latestFix

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
    }
}
