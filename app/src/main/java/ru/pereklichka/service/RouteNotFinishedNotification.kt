package ru.pereklichka.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.pereklichka.MainActivity
import ru.pereklichka.R

/**
 * Напоминание «Маршрут не завершён, откройте приложение». [RouteService] показывает его, когда поднялся без
 * человека у экрана и не пишет трек или не смог запуститься вовсе. Канал со звуком по умолчанию, потому что
 * в тихом канале службы напоминание не заметят, а каналы `AndroidNotifier` заняты событиями группы.
 */
object RouteNotFinishedNotification {

    private const val ID = 3
    private const val CHANNEL_ID = "route_not_finished"

    fun show(context: Context) {
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.route_not_finished_title))
            .setContentText(context.getString(R.string.route_not_finished_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.route_not_finished_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
