package com.v2ray.ang.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.ui.main.MainActivity

/** Shared, idempotent controls for the notification and Quick Settings tiles. */
class AicpControlActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        perform(context, intent?.action ?: return)
    }

    companion object {
        const val ACTION_PAUSE = "com.aicp.vpn.action.PAUSE"
        const val ACTION_PLAY = "com.aicp.vpn.action.PLAY"
        const val ACTION_STOP = "com.aicp.vpn.action.STOP"
        private const val PREFS = "aicp_vpn_controls"
        private const val KEY_PAUSED = "paused"
        private const val PAUSED_NOTIFICATION_ID = 7103

        fun isPaused(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED, false)

        private fun setPaused(context: Context, paused: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PAUSED, paused).apply()
        }

        @JvmStatic
        fun perform(context: Context, action: String) {
            val app = context.applicationContext
            when (action) {
                ACTION_PAUSE -> {
                    if (isPaused(app)) { showPausedNotification(app); return }
                    setPaused(app, true)
                    LauncherManager.stopService(app)
                    showPausedNotification(app)
                }
                ACTION_PLAY -> {
                    if (!isPaused(app)) {
                        LauncherManager.startServiceFromToggle(app)
                        return
                    }
                    setPaused(app, false)
                    cancelPausedNotification(app)
                    if (!LauncherManager.startServiceFromToggle(app)) {
                        setPaused(app, true)
                        showPausedNotification(app)
                    }
                }
                ACTION_STOP -> {
                    setPaused(app, false)
                    cancelPausedNotification(app)
                    LauncherManager.stopService(app)
                }
            }
        }

        private fun showPausedNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = AppConfig.RAY_NG_CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(NotificationChannel(channelId,
                    context.getString(R.string.notification_channel_service), NotificationManager.IMPORTANCE_LOW))
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun actionIntent(action: String, requestCode: Int) = PendingIntent.getBroadcast(context, requestCode,
                Intent(context, AicpControlActionReceiver::class.java).setAction(action), flags)
            val openApp = PendingIntent.getActivity(context, 7100, Intent(context, MainActivity::class.java), flags)
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_aicp)
                .setContentTitle(context.getString(R.string.aicp_vpn_paused_title))
                .setContentText(context.getString(R.string.aicp_vpn_paused_summary))
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(R.drawable.ic_play_24dp, context.getString(R.string.aicp_vpn_play),
                    actionIntent(ACTION_PLAY, 7101))
                .addAction(R.drawable.ic_stop_24dp, context.getString(R.string.aicp_vpn_stop),
                    actionIntent(ACTION_STOP, 7102))
                .build()
            manager.notify(PAUSED_NOTIFICATION_ID, notification)
        }

        private fun cancelPausedNotification(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSED_NOTIFICATION_ID)
        }
    }
}
