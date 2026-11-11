package ru.pereklichka.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Получает deleteIntent смахнутого уведомления тревоги и передаёт ключ в [AndroidNotifier.repostAlarm]. Снаружи
 * приложения приёмник недоступен (`exported="false"`), интент шлёт система от имени приложения. Если процесс
 * успели убить, у нового [AndroidNotifier] список тревог пуст и возвращать нечего: активные тревоги снова покажет
 * `StatusMonitor`, когда служба поднимется.
 */
@AndroidEntryPoint
class AlarmSwipeReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: AndroidNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        notifier.repostAlarm(key)
    }

    companion object {
        private const val EXTRA_KEY = "ru.pereklichka.ALARM_KEY"

        fun intent(context: Context, key: String): Intent =
            Intent(context, AlarmSwipeReceiver::class.java).putExtra(EXTRA_KEY, key)
    }
}
