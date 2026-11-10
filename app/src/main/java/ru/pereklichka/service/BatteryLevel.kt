package ru.pereklichka.service

import android.content.Context
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Читает заряд батареи для точек трека ([TrackRecorder]) и своего состояния ([SelfStateProducer]). */
class BatteryLevel @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Заряд в процентах от 0 до 100 или null, если система его не сообщила. Других значений сервер не принимает.
     * При ошибке `getIntProperty` возвращает `Integer.MIN_VALUE` (при targetSdk 28 и выше), такое значение
     * отсекается.
     */
    fun percent(): Int? {
        val capacity = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return capacity?.takeIf { it in 0..100 }
    }
}
