package it.dogior.hadEnough.localsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object StreamCenterLocalSyncNotifier {
    private const val CHANNEL_ID = "streamcenter_local_sync_v2"
    private const val NOTIFICATION_ID = 48_211
    private var channelReady = false

    fun notifySync(context: Context, title: String, message: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val completedAt = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date())
        val completedMessage = "$message · $completedAt"
        val notificationSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(completedMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(completedMessage))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(notificationSound)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Sync Locale",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Notifiche della sincronizzazione locale automatica"
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .build(),
                        )
                    },
                )
            }
        }
        channelReady = true
    }
}
