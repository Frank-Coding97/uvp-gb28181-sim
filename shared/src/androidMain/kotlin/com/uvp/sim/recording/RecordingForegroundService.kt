package com.uvp.sim.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 录像保活前台 Service。
 *
 * Android 14+(API 34) 严格要求:用 CameraX 在后台录视频必须有
 * FOREGROUND_SERVICE_TYPE_CAMERA(+MICROPHONE)且对应权限声明。否则切后台 / 锁屏
 * 会被系统回收 camera,录像中断。
 *
 * 该 Service 不直接操作相机 — 真正录像在 [AndroidRecordingService] 里走 CameraX,
 * 这里只是给 Android 一个"应用持有相机用于前台任务"的承诺,顺带给用户看通知栏。
 *
 * 启停由 [AndroidRecordingService.start] / [AndroidRecordingService.stop] 触发,
 * AndroidManifest 已声明 service + 各 type 权限。
 */
class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: 必须显式传 type
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notif, type)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "录像中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "录像运行时显示在通知栏,确保后台不被回收"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UVP Sim · 录像中")
            .setContentText("正在录像,请勿强退应用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)  // 系统自带图标避免引资源
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording_foreground"
        const val NOTIFICATION_ID = 0x52454300  // 'REC\0' magic

        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
