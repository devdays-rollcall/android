package ru.pereklichka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Запуск службы после перезагрузки телефона посреди маршрута. В манифесте приёмник выключен
 * (`android:enabled="false"`), пока он не готов: иначе телефон падал бы при каждой загрузке.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // TODO(Василиса): после загрузки поднять службу, если маршрут активной группы не завершён.
        //  Только тип connectedDevice, для GPS уведомление «Маршрут не завершён, откройте приложение»; потом включить
        //  приёмник в манифесте. docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): после загрузки поднять службу, если маршрут активной группы не завершён.")
    }
}
