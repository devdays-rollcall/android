package ru.pereklichka.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Проверяет, может ли Nearby работать: включён ли Bluetooth и есть ли Google Play Services. Вызывают
 * [NearbyPeerTransport] перед стартом и экран «Настройки». Nearby сам Bluetooth не включает, поэтому при выключенном
 * радио интерфейс просит человека включить его. Без Google Play Services Nearby не работает вовсе.
 */
class RadioCheck @Inject constructor(@ApplicationContext private val context: Context) {

    fun isBluetoothEnabled(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}
