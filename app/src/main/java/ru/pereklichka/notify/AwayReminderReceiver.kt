package ru.pereklichka.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.pereklichka.actions.GroupActions
import ru.pereklichka.di.ApplicationScope
import javax.inject.Inject

/**
 * Кнопки уведомления «Вернулись?»: «Я вернулся» отменяет отлучку ([GroupActions.cancelAway]), «Ещё 10 минут»
 * объявляет новую на [EXTEND_MINUTES] минут. Уведомление убирается сразу, не дожидаясь, пока `StatusMonitor`
 * увидит новое событие. Снаружи приложения приёмник недоступен (`exported="false"`), интенты шлёт система
 * от имени приложения. Если активной группы уже нет, событие писать некуда и нажатие ничего не меняет.
 */
@AndroidEntryPoint
class AwayReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var actions: GroupActions
    @Inject lateinit var notifier: AndroidNotifier
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    /** Запись события в базу укладывается в 10 с, которые `goAsync` даёт приёмнику. */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_BACK && action != ACTION_EXTEND) return
        notifier.dismiss(NotifyEvent.AWAY_ENDING_KEY)
        val pending = goAsync()
        scope.launch {
            try {
                if (action == ACTION_BACK) actions.cancelAway() else actions.announceAway(EXTEND_MINUTES)
            } catch (e: IllegalStateException) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_BACK = "ru.pereklichka.action.AWAY_BACK"
        const val ACTION_EXTEND = "ru.pereklichka.action.AWAY_EXTEND"
        const val EXTEND_MINUTES = 10

        fun intent(context: Context, action: String): Intent =
            Intent(context, AwayReminderReceiver::class.java).setAction(action)
    }
}
