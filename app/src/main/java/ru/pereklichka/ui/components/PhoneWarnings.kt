package ru.pereklichka.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.transport.RadioCheck

/** Что на телефоне мешает быть на связи или услышать тревогу. Показывается баннером над списком группы. */
enum class PhoneWarning(val title: String, val subtitle: String) {
    SERVICE_NOT_STARTED(
        "Приложение не работает в фоне",
        "Нет разрешений на обмен и геолокацию, поэтому телефон не найдёт группу и не запишет трек. Нажмите, чтобы выдать",
    ),
    PERMISSIONS_MISSING(
        "Не все разрешения выданы",
        "Без обмена телефон не найдёт группу, без геолокации не запишет трек. Нажмите, чтобы выдать",
    ),
    BLUETOOTH_OFF(
        "Включите Bluetooth",
        "Без него телефон не найдёт группу. Нажмите, чтобы открыть настройки",
    ),
    NOTIFICATIONS_OFF(
        "Уведомления выключены",
        "Тревога придёт только звуком, пока приложение работает. Нажмите, чтобы разрешить",
    ),
    TOTAL_SILENCE(
        "Включена полная тишина",
        "В режиме «Не беспокоить» без исключений тревогу не будет слышно. Нажмите, чтобы изменить",
    ),
    ;

    /** Экран настроек, где это исправляется; null, если исправляется на экране разрешений приложения. */
    fun settingsIntent(context: Context): Intent? = when (this) {
        SERVICE_NOT_STARTED, PERMISSIONS_MISSING -> null
        BLUETOOTH_OFF -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        NOTIFICATIONS_OFF -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        TOTAL_SILENCE -> Intent(Settings.ACTION_SOUND_SETTINGS)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Предупреждения для экрана группы. Пересчитываются при каждом новом [view], а он приходит раз в 2 секунды, так что
 * включённый Bluetooth пропадёт из списка почти сразу. После завершения маршрута о разрешениях не предупреждаем:
 * служба уже не нужна. В превью предупреждений нет.
 */
@Composable
fun rememberPhoneWarnings(view: GroupView, serviceStarted: Boolean?): List<PhoneWarning> {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return emptyList()
    val radio = remember(context) { RadioCheck(context.applicationContext) }
    return remember(view, serviceStarted) {
        buildList {
            if (view.route != RouteState.FINISHED) permissionWarning(context, serviceStarted)?.let(::add)
            if (!radio.isBluetoothEnabled()) add(PhoneWarning.BLUETOOTH_OFF)
            if (!PhoneChecks.notificationsEnabled(context)) add(PhoneWarning.NOTIFICATIONS_OFF)
            if (PhoneChecks.totalSilence(context)) add(PhoneWarning.TOTAL_SILENCE)
        }
    }
}

/**
 * Предупреждение о разрешениях для экранов QR и завершения: служба не запустилась ([serviceStarted] равен false)
 * или не выданы обмен и геолокация. null, если всё выдано, и в превью.
 */
@Composable
fun rememberPermissionWarning(serviceStarted: Boolean?): PhoneWarning? {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return null
    return permissionWarning(context, serviceStarted)
}

private fun permissionWarning(context: Context, serviceStarted: Boolean?): PhoneWarning? = when {
    serviceStarted == false -> PhoneWarning.SERVICE_NOT_STARTED
    RequiredPermissions.missing(context, RequiredPermissions.exchange() + RequiredPermissions.location()).isNotEmpty() ->
        PhoneWarning.PERMISSIONS_MISSING
    else -> null
}
