package com.whale.hand.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class FloatingService : Service() {

    companion object {
        const val NOTIF_ID = 1001

        @Volatile
        private var current: FloatingService? = null

        var onStopClick: (() -> Unit)?
            get() = OverlayManager.onStopClick
            set(v) { OverlayManager.onStopClick = v }

        fun start(ctx: Context) {
            OverlayManager.showStatus()
            runCatching {
                ctx.startForegroundService(Intent(ctx, FloatingService::class.java))
            }
        }

        fun stop(ctx: Context) {
            OverlayManager.hideStatus()
            runCatching { ctx.stopService(Intent(ctx, FloatingService::class.java)) }
        }

        fun update(text: String) {
            OverlayManager.update(text)
            runCatching { current?.updateNotification(text) }
        }

        fun showTap(x: Int, y: Int, label: String? = null) =
            OverlayManager.showTap(x, y, label)

        fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, label: String? = null) =
            OverlayManager.showSwipe(x1, y1, x2, y2, label)

        fun hideMarker() = OverlayManager.hideMarker()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        startForeground(NOTIF_ID, buildNotification())
        }

    /** 把最新一步写进通知栏（低成本版的「原子岛」进度） */
    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_AGENT") {
            onStopClick?.invoke()
        }
        return START_STICKY
    }

    private fun buildNotification(status: String = "AI 正在操作你的手机，点通知或悬浮条可停止"): Notification {
        val channelId = "whale_hand"
        val nm = getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "运行状态", NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = Intent(this, FloatingService::class.java)
            .setAction("STOP_AGENT")

        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("WhaleHand 运行中")
            .setContentText(status.take(60))
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPending
            )

        if (Build.VERSION.SDK_INT >= 36) {
            b.setShortCriticalText("WhaleHand 运行中")
        }

        return b.build()
    }

    override fun onDestroy() {
        current = null
        super.onDestroy()
    }
}
