package ru.pereklichka.ui.settings

import ru.pereklichka.ui.components.plural

/**
 * Что [SettingsViewModel] прочитал с телефона для раздела «Проверка телефона». Отдельно от Android, чтобы правила
 * [phoneChecks] и [phoneSummary] проверялись тестом без телефона.
 */
data class PhoneReadings(
    /** Выданы разрешения обмена из `RequiredPermissions.exchange()`. */
    val exchangeGranted: Boolean,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    /** Геолокация включена в системе. */
    val locationEnabled: Boolean,
    /** «Разрешить всегда»; null до Android 10, где отдельного разрешения нет. */
    val backgroundLocation: Boolean?,
    val bluetoothEnabled: Boolean,
    val playServices: Boolean,
    val notifications: Boolean,
    /** «Не беспокоить» в режиме полной тишины. */
    val totalSilence: Boolean,
    /** Приложение в исключениях из оптимизации батареи. */
    val batteryExempt: Boolean,
)

/**
 * Насколько строка проверки в порядке. REQUIRED значит, что не выдано обязательное разрешение, MANUAL значит,
 * что приложение проверить это не может и человек смотрит сам.
 */
enum class CheckLevel { OK, WARNING, REQUIRED, MANUAL }

/** Строки раздела «Проверка телефона» в порядке макета. */
enum class CheckKind(val title: String) {
    BLUETOOTH("Bluetooth"),
    LOCATION("Геолокация"),
    NOTIFICATIONS("Уведомления"),
    DO_NOT_DISTURB("«Не беспокоить»"),
    BACKGROUND("Работа в фоне"),
    AUTOSTART("Автозапуск"),
}

/** Куда ведёт «Исправить»: на экран разрешений приложения или на страницу системных настроек. */
enum class Fix { PERMISSIONS, BLUETOOTH_SETTINGS, LOCATION_SETTINGS, NOTIFICATION_SETTINGS, SOUND_SETTINGS, BATTERY, AUTOSTART }

/** Строка проверки: [fix] равен null, когда исправить с телефона нельзя. */
data class PhoneCheck(val kind: CheckKind, val level: CheckLevel, val subtitle: String, val fix: Fix?)

/** Итог проверок для карточки над списком. */
data class PhoneSummary(val level: CheckLevel, val title: String, val subtitle: String)

/** Строки «Проверки телефона» по прочитанному [r]. Автозапуск проверить нельзя, он всегда MANUAL. */
fun phoneChecks(r: PhoneReadings): List<PhoneCheck> = listOf(
    when {
        !r.exchangeGranted -> PhoneCheck(CheckKind.BLUETOOTH, CheckLevel.REQUIRED, "Нет разрешения искать телефоны рядом", Fix.PERMISSIONS)
        !r.bluetoothEnabled -> PhoneCheck(CheckKind.BLUETOOTH, CheckLevel.WARNING, "Выключен, телефон не найдёт группу", Fix.BLUETOOTH_SETTINGS)
        !r.playServices -> PhoneCheck(CheckKind.BLUETOOTH, CheckLevel.WARNING, "Нет Google Play Services, обмен не работает", null)
        else -> PhoneCheck(CheckKind.BLUETOOTH, CheckLevel.OK, "Включён", null)
    },
    when {
        !r.fineLocation && r.coarseLocation ->
            PhoneCheck(CheckKind.LOCATION, CheckLevel.REQUIRED, "Только приблизительная, трек не записать", Fix.PERMISSIONS)
        !r.fineLocation -> PhoneCheck(CheckKind.LOCATION, CheckLevel.REQUIRED, "Нет разрешения, трек не записать", Fix.PERMISSIONS)
        !r.locationEnabled -> PhoneCheck(CheckKind.LOCATION, CheckLevel.WARNING, "Выключена в системе, GPS молчит", Fix.LOCATION_SETTINGS)
        r.backgroundLocation == false ->
            PhoneCheck(CheckKind.LOCATION, CheckLevel.WARNING, "Точная, без «Разрешить всегда»", Fix.PERMISSIONS)
        r.backgroundLocation == true -> PhoneCheck(CheckKind.LOCATION, CheckLevel.OK, "Точная, всегда", null)
        else -> PhoneCheck(CheckKind.LOCATION, CheckLevel.OK, "Точная", null)
    },
    if (r.notifications) {
        PhoneCheck(CheckKind.NOTIFICATIONS, CheckLevel.OK, "Разрешены", null)
    } else {
        PhoneCheck(CheckKind.NOTIFICATIONS, CheckLevel.WARNING, "Выключены, тревога придёт только звуком", Fix.NOTIFICATION_SETTINGS)
    },
    if (r.totalSilence) {
        PhoneCheck(CheckKind.DO_NOT_DISTURB, CheckLevel.WARNING, "Полная тишина, тревогу не слышно", Fix.SOUND_SETTINGS)
    } else {
        PhoneCheck(CheckKind.DO_NOT_DISTURB, CheckLevel.OK, "Тревогу будет слышно", null)
    },
    if (r.batteryExempt) {
        PhoneCheck(CheckKind.BACKGROUND, CheckLevel.OK, "Телефон не уснёт в кармане", null)
    } else {
        PhoneCheck(CheckKind.BACKGROUND, CheckLevel.WARNING, "Система может усыпить обмен в кармане", Fix.BATTERY)
    },
    PhoneCheck(CheckKind.AUTOSTART, CheckLevel.MANUAL, "Проверьте вручную на Xiaomi и Honor", Fix.AUTOSTART),
)

/**
 * Карточка над списком: красная, если не выдано обязательное разрешение, жёлтая со счётчиком остальных проблем,
 * зелёная, если проблем нет. Автозапуск в счёт не входит.
 */
fun phoneSummary(checks: List<PhoneCheck>): PhoneSummary {
    if (checks.any { it.level == CheckLevel.REQUIRED }) {
        return PhoneSummary(
            CheckLevel.REQUIRED,
            "Телефон не готов к походу",
            "Без обязательных разрешений телефон не найдёт группу или не запишет трек",
        )
    }
    val problems = checks.count { it.level == CheckLevel.WARNING }
    if (problems == 0) return PhoneSummary(CheckLevel.OK, "Телефон готов к походу", "Все проверки пройдены")
    return PhoneSummary(
        CheckLevel.WARNING,
        "Телефон почти готов",
        "$problems ${plural(problems, "проблема", "проблемы", "проблем")} " +
            "${plural(problems, "может", "могут", "могут")} помешать услышать тревогу",
    )
}
