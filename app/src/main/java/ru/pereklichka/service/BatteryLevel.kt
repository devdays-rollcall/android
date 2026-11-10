package ru.pereklichka.service

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Заряд батареи для точек трека и своего состояния. */
class BatteryLevel @Inject constructor(@ApplicationContext private val context: Context) {

    /** Заряд от 0 до 100; null, если система его не сообщила. Сервер не принимает другие значения. */
    fun percent(): Int? {
        val capacity = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // При ошибке getIntProperty возвращает Integer.MIN_VALUE (targetSdk 28 и выше).
        return capacity?.takeIf { it in 0..100 }
    }
}
