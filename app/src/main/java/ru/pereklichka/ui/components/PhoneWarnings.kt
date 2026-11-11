package ru.pereklichka.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.transport.RadioCheck

/** Что на телефоне мешает быть на связи или услышать тревогу. Показывается баннером над списком группы. */
enum class PhoneWarning(val title: String, val subtitle: String) {
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

    /** Экран настроек, где это исправляется. */
    fun settingsIntent(context: Context): Intent = when (this) {
        BLUETOOTH_OFF -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        NOTIFICATIONS_OFF -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        TOTAL_SILENCE -> Intent(Settings.ACTION_SOUND_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Предупреждения для экрана группы. Пересчитываются при каждом новом [view], а он приходит раз в 2 секунды, так что
 * включённый Bluetooth пропадёт из списка почти сразу. В превью предупреждений нет.
 */
@Composable
fun rememberPhoneWarnings(view: GroupView): List<PhoneWarning> {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return emptyList()
    val radio = remember(context) { RadioCheck(context.applicationContext) }
    return remember(view) {
        buildList {
            if (!radio.isBluetoothEnabled()) add(PhoneWarning.BLUETOOTH_OFF)
            if (!PhoneChecks.notificationsEnabled(context)) add(PhoneWarning.NOTIFICATIONS_OFF)
            if (PhoneChecks.totalSilence(context)) add(PhoneWarning.TOTAL_SILENCE)
        }
    }
}
