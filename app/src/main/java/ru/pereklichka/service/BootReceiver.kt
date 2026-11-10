package ru.pereklichka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Поднимает [RouteService] после перезагрузки телефона посреди маршрута. Система вызывает его по BOOT_COMPLETED.
 * В манифесте приёмник выключен (`android:enabled="false"`), пока он не готов, иначе приложение падало бы
 * при каждой загрузке телефона.
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // TODO(Василиса): после загрузки поднять службу, если маршрут активной группы не завершён.
        //  Только тип connectedDevice и уведомление «Маршрут не завершён, откройте приложение», потом включить
        //  приёмник в манифесте.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): после загрузки поднять службу, если маршрут активной группы не завершён.")
    }
}
