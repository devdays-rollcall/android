package ru.pereklichka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.pereklichka.MainActivity
import ru.pereklichka.R

/**
 * Постоянное уведомление foreground service, которое [RouteService] показывает весь маршрут. Канал тихий,
 * потому что звук нужен только тревогам, а у них свои каналы в `AndroidNotifier`. Нажатие открывает приложение.
 */
object ServiceNotification {

    const val ID = 1
    private const val CHANNEL_ID = "route"

    /** Уведомление для `startForeground`. Канал создаётся при первом вызове. */
    fun build(context: Context): Notification {
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
