package ru.pereklichka.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Списки разрешений, которые приложение запрашивает во время работы, по назначению и версиям Android.
 * Все они объявлены в манифесте. Список Nearby взят из документации Nearby Connections. Камеры здесь нет,
 * сканер QR спрашивает её сам при открытии.
 *
 * Читают их [PermissionsScreen] (что спросить), `RouteService` (с какими типами запускаться) и диагностика.
 * Параметр `sdk` нужен тестам, в приложении остаётся версия телефона. Константы новых разрешений компилятор
 * подставляет строками, а версию проверяет код по `sdk`, поэтому предупреждение InlinedApi подавлено.
 */
@SuppressLint("InlinedApi")
object RequiredPermissions {

    /**
     * Обмен через Nearby. `ACCESS_LOCAL_NETWORK` из списка Nearby объявлен в манифесте, но не запрашивается:
     * Android 17 требует его только у приложений с targetSdk 37, а у нас 36.
     */
    fun exchange(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdk >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        // До Android 13 сканирование Bluetooth и Wi-Fi, через которое Nearby ищет соседей, требует разрешения на геолокацию.
        if (sdk < Build.VERSION_CODES.TIRAMISU) addAll(location())
    }

    /** GPS для трека и позиции. С Android 12 точную геолокацию запрашивают вместе с приблизительной. */
    fun location(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * «Разрешить всегда»: с ним служба после перезапуска поднимается и с типом location. Сейчас его не
     * спрашивают. Если начнут, запрашивать его нужно отдельно и после [location].
     */
    fun backgroundLocation(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList()

    /** Без уведомлений нет звука тревоги. До Android 13 разрешение не нужно. */
    fun notifications(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Те из [permissions], которые ещё не выданы. */
    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** true, если выданы все [permissions]. */
    fun allGranted(context: Context, permissions: List<String>): Boolean = missing(context, permissions).isEmpty()
}
