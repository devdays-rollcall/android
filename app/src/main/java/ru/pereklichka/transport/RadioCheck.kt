package ru.pereklichka.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Проверки до старта Nearby. С конца 2026 года Nearby сам не включает Bluetooth, поэтому при выключенном радио
 * экран просит человека включить его. Без Google Play Services Nearby не работает вовсе.
 */
class RadioCheck @Inject constructor(@ApplicationContext private val context: Context) {

    fun isBluetoothEnabled(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}
