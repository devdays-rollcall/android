package ru.pereklichka.permissions

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Списки разрешений по версиям Android, сверенные с документацией Nearby Connections. */
class RequiredPermissionsTest {

    @Test
    fun `до Android 12 обмену нужна геолокация`() {
        assertEquals(RequiredPermissions.location(), RequiredPermissions.exchange(sdk = 30))
    }

    @Test
    fun `на Android 12 нужны Bluetooth-разрешения и геолокация`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) +
                RequiredPermissions.location(),
            RequiredPermissions.exchange(sdk = 31),
        )
    }

    @Test
    fun `с Android 13 вместо геолокации NEARBY_WIFI_DEVICES`() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            RequiredPermissions.exchange(sdk = 33),
        )
    }

    @Test
    fun `уведомления и фоновая геолокация зависят от версии`() {
        assertEquals(emptyList(), RequiredPermissions.notifications(sdk = 32))
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), RequiredPermissions.notifications(sdk = 33))
        assertEquals(emptyList(), RequiredPermissions.backgroundLocation(sdk = 28))
        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RequiredPermissions.backgroundLocation(sdk = 29))
    }
}
