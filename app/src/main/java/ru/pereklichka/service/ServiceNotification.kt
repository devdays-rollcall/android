package ru.pereklichka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.pereklichka.MainActivity
import ru.pereklichka.R
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState

/**
 * Постоянное уведомление foreground service, которое [RouteService] показывает весь маршрут. Канал тихий,
 * потому что звук нужен только тревогам, а у них свои каналы в `AndroidNotifier`. Нажатие открывает приложение.
 * Текст служба меняет через [update] на сводку [statusText]: кто на связи и кого не слышно.
 */
object ServiceNotification {

    const val ID = 1
    private const val CHANNEL_ID = "route"

    /** Уведомление для `startForeground` с текстом [text], по умолчанию общим. Канал создаётся при первом вызове. */
    fun build(context: Context, text: String = context.getString(R.string.service_notification_text)): Notification {
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
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Меняет текст уже показанного уведомления. Без разрешения на уведомления ничего не делает. */
    fun update(context: Context, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(ID, build(context, text))
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и показом: текст обновится при следующем изменении.
        }
    }

    /**
     * Сводка для текста уведомления: «На связи 3 из 3», «На связи 2 из 4 · не слышно: Валентин, Ольга». Отлучка
     * считается связью, потому что «Не слышно» до её конца не наступает.
     */
    fun statusText(view: GroupView): String {
        val others = view.members.filterNot { it.isMe }
        if (view.route == RouteState.FINISHED) return "Маршрут завершён, передаём это остальным"
        if (others.isEmpty()) return "В группе пока никого нет"
        val lost = others.filter { it.presence == Presence.LOST }
        val onLine = "На связи ${others.size - lost.size} из ${others.size}"
        return if (lost.isEmpty()) onLine else "$onLine · не слышно: ${lost.joinToString { it.name }}"
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
