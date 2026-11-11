package ru.pereklichka.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Проверки телефона, от которых зависит, услышит ли человек тревогу. Читают их экран группы (предупреждения),
 * диагностика (раздел «Телефон») и [AndroidNotifier].
 */
object PhoneChecks {

    /** Уведомления приложения разрешены: на Android 13 и новее это `POST_NOTIFICATIONS`, раньше переключатель в настройках. */
    fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * «Не беспокоить» в режиме полной тишины (`INTERRUPTION_FILTER_NONE`): не проходит ничего, даже будильники.
     * В остальных режимах тревога проходит, потому что у её канала звук с `USAGE_ALARM`, а будильники разрешены
     * по умолчанию.
     */
    fun totalSilence(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE
}
