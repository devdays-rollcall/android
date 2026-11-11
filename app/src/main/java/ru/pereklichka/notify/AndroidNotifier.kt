package ru.pereklichka.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.pereklichka.MainActivity
import ru.pereklichka.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Notifier] на системных уведомлениях Android: тревоги, «Не слышно», перекличка и подсказки о близости.
 * Вызывает его `StatusMonitor`. Тихий канал службы создаёт `ServiceNotification`, остальные каналы создаются здесь.
 *
 * Каналы:
 * - «Тревоги» ([CHANNEL_ALARMS]): тревоги и SOS, высокая важность, звук будильника с `USAGE_ALARM`. Так тревога
 *   проходит «Не беспокоить», если в нём разрешены будильники (по умолчанию разрешены).
 * - «Участники» ([CHANNEL_MEMBERS]): «Не слышно» и подсказка «Рядом телефон участника».
 * - «Перекличка» ([CHANNEL_ROLLCALL]).
 *
 * У всех уведомлений один id, различает их tag = [NotifyEvent.key]: повторный [show] с тем же ключом обновляет
 * уведомление, а `setOnlyAlertOnce` не даёт звуку повторяться.
 *
 * Важность и звук канала после создания меняет только пользователь. Если поменять их здесь, на установленном
 * приложении ничего не изменится: нужно сменить id канала (отсюда суффикс версии) или переустановить приложение.
 *
 * Без `POST_NOTIFICATIONS` (Android 13 и новее) уведомлений нет совсем. Тогда звук тревоги проигрывает само
 * приложение через [RingtoneManager] и останавливает его на [NotifyEvent.AlarmResolved] этой тревоги.
 *
 * Уведомление тревоги постоянное, но с Android 14 его можно смахнуть на разблокированном экране. Тогда
 * [AlarmSwipeReceiver] вызывает [repostAlarm], и тревога, которую ещё не сняли, возвращается в шторку без звука.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Singleton
class AndroidNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {

    private val manager = NotificationManagerCompat.from(context)

    /** Тревоги, которые сейчас звучат без уведомлений, по ключу. */
    private val ringing = mutableMapOf<String, Ringtone>()

    /** Показанные и ещё не снятые тревоги по ключу: их возвращает [repostAlarm]. */
    private val activeAlarms = mutableMapOf<String, NotifyEvent.AlarmRaised>()

    @Volatile
    private var channelsCreated = false

    override fun show(event: NotifyEvent) {
        ensureChannels()
        when (event) {
            is NotifyEvent.AlarmRaised -> showAlarm(event)
            is NotifyEvent.AlarmResolved -> dismiss(event.key)
            is NotifyEvent.MemberLost -> post(
                event.key,
                NotificationCompat.Builder(context, CHANNEL_MEMBERS)
                    .setContentTitle("${event.name}: не слышно")
                    .setContentText("Группа давно не слышала этого участника. Откройте карточку")
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(open(event.key, NotificationTarget.Member(event.member))),
            )
            is NotifyEvent.RollcallStarted -> post(
                event.key,
                NotificationCompat.Builder(context, CHANNEL_ROLLCALL)
                    .setContentTitle("Перекличка")
                    .setContentText("Руководитель проверяет группу. Нажмите «Я здесь»")
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(open(event.key, NotificationTarget.Rollcall)),
            )
            is NotifyEvent.NearbyConfirm -> post(
                event.key,
                NotificationCompat.Builder(context, CHANNEL_MEMBERS)
                    .setContentTitle("Рядом телефон участника ${event.name}")
                    .setContentText("Вы видите этого человека?")
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(open(event.key, NotificationTarget.Alarms)),
            )
        }
    }

    // Под одним замком с repostAlarm: иначе смахнутая тревога могла бы вернуться в шторку уже после снятия.
    @Synchronized
    override fun dismiss(key: String) {
        activeAlarms.remove(key)
        manager.cancel(key, NOTIFICATION_ID)
        stopRinging(key)
    }

    /** Возвращает в шторку смахнутое уведомление тревоги [key] без звука, если тревогу ещё не сняли. */
    @Synchronized
    fun repostAlarm(key: String) {
        val event = activeAlarms[key] ?: return
        ensureChannels()
        showAlarm(event, silent = true)
    }

    @Synchronized
    private fun showAlarm(event: NotifyEvent.AlarmRaised, silent: Boolean = false) {
        activeAlarms[event.key] = event
        val (title, text) = when {
            event.isAboutMe && event.isSos -> "Вы отправили SOS" to "Группа получила сигнал. Если помощь не нужна, отмените его"
            event.isAboutMe -> "Группа объявила тревогу о вас" to "Если вы в порядке, откройте и нажмите «Я в порядке»"
            event.isSos -> "SOS: ${event.targetName}" to "Участнику нужна помощь. Откройте, чтобы ответить"
            else -> "Тревога: ${event.targetName}" to "Откройте, чтобы ответить «Вижу, в порядке»"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Уведомление уходит само, когда тревогу снимут. Смахнуть его можно с Android 14, и тогда deleteIntent
            // возвращает его через repostAlarm.
            .setOngoing(true)
            .setSilent(silent)
            .setContentIntent(open(event.key, NotificationTarget.Alarms))
            .setDeleteIntent(swipeIntent(event.key))
        if (!post(event.key, builder, autoCancel = false) && !silent) startRinging(event.key)
    }

    /** Показывает уведомление ([autoCancel]: убрать по нажатию); false, если уведомления выключены и показать нельзя. */
    @SuppressLint("MissingPermission") // Разрешение проверяет PhoneChecks: на Android 13+ оно учитывает POST_NOTIFICATIONS.
    private fun post(key: String, builder: NotificationCompat.Builder, autoCancel: Boolean = true): Boolean {
        if (!PhoneChecks.notificationsEnabled(context)) return false
        manager.notify(
            key,
            NOTIFICATION_ID,
            builder
                .setSmallIcon(R.drawable.ic_notification)
                .setOnlyAlertOnce(true)
                .setAutoCancel(autoCancel)
                .build(),
        )
        return true
    }

    /** Нажатие открывает приложение на нужном экране. requestCode свой у каждого ключа, иначе extras перезапишутся. */
    private fun open(key: String, target: NotificationTarget): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationTarget.put(intent, target)
        return PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** deleteIntent тревоги [key]: сообщает [AlarmSwipeReceiver], что её смахнули. requestCode свой у каждого ключа, как в [open]. */
    private fun swipeIntent(key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        AlarmSwipeReceiver.intent(context, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Synchronized
    private fun startRinging(key: String) {
        if (key in ringing) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        ringtone.audioAttributes = alarmAudio()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
        ringtone.play()
        ringing[key] = ringtone
    }

    @Synchronized
    private fun stopRinging(key: String) {
        ringing.remove(key)?.stop()
    }

    private fun ensureChannels() {
        if (channelsCreated) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarms = NotificationChannel(
            CHANNEL_ALARMS,
            context.getString(R.string.channel_alarms),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarms_description)
            setSound(alarmSound, alarmAudio())
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val members = NotificationChannel(
            CHANNEL_MEMBERS,
            context.getString(R.string.channel_members),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_members_description) }
        val rollcall = NotificationChannel(
            CHANNEL_ROLLCALL,
            context.getString(R.string.channel_rollcall),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_rollcall_description) }
        system.createNotificationChannels(listOf(alarms, members, rollcall))
        channelsCreated = true
    }

    private fun alarmAudio(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private companion object {
        /** Один id на всех: уведомления различает tag. 1 занят постоянным уведомлением службы. */
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ALARMS = "alarms_v1"
        const val CHANNEL_MEMBERS = "members_v1"
        const val CHANNEL_ROLLCALL = "rollcall_v1"
    }
}
