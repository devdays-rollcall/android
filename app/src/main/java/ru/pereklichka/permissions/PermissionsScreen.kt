package ru.pereklichka.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.service.PowerTools
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Экран разрешений. Стартовый экран открывает его сам, если не выданы обмен или геолокация, а ещё его открывают
 * пункт «Разрешения» на старте и в меню группы и предупреждения на экранах группы.
 * «Продолжить» доступна, когда выданы обмен и точная геолокация, а геолокация включена в системе: без этого
 * телефон не найдёт группу и не запишет трек. Уведомления, «Разрешить всегда», работа в фоне и автозапуск
 * желательны, но без них продолжить можно.
 *
 * Камеру экран не спрашивает, сканер QR запрашивает её сам при открытии.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val powerTools = remember(context) { PowerTools(context.applicationContext) }
    var state by remember { mutableStateOf(if (inPreview) PermissionsState.ALL_GRANTED else readState(context, powerTools)) }
    // Человек мог выдать разрешение в настройках или включить геолокацию, поэтому состояние читается при каждом возврате.
    LifecycleResumeEffect(Unit) {
        if (!inPreview) state = readState(context, powerTools)
        onPauseOrDispose { }
    }
    // После отказа система может больше не показывать диалог, поэтому вторая кнопка ведёт в настройки приложения.
    var essentialAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    var backgroundAsked by rememberSaveable { mutableStateOf(false) }
    var autostartNotFound by rememberSaveable { mutableStateOf(false) }

    val essentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        essentialAsked = true
        state = readState(context, powerTools)
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsAsked = true
        state = readState(context, powerTools)
    }

    // «Разрешить всегда» нельзя спросить вместе с обычной геолокацией: с Android 11 система по этому запросу
    // открывает страницу настроек, и только после выданной точной геолокации.
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        backgroundAsked = true
        state = readState(context, powerTools)
    }

    PermissionsContent(
        state = state,
        essentialAsked = essentialAsked,
        notificationsAsked = notificationsAsked,
        backgroundAsked = backgroundAsked,
        autostartNotFound = autostartNotFound,
        onRequestEssential = {
            essentialLauncher.launch((RequiredPermissions.exchange() + RequiredPermissions.location()).distinct().toTypedArray())
        },
        onRequestNotifications = { notificationsLauncher.launch(RequiredPermissions.notifications().toTypedArray()) },
        onRequestBackground = { backgroundLauncher.launch(RequiredPermissions.backgroundLocation().toTypedArray()) },
        onOpenAppSettings = { context.openSettings(appSettingsIntent(context)) },
        onOpenLocationSettings = { context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        onOpenNotificationSettings = {
            context.openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        onBattery = {
            if (!context.openSettings(powerTools.ignoreBatteryOptimizationsIntent())) context.openSettings(appSettingsIntent(context))
        },
        onAutostart = { autostartNotFound = !powerTools.openAutostartSettings(context) },
        onDone = onDone,
    )
}

/** Что выдано на этот момент. */
private data class PermissionsState(
    val exchangeAndLocation: Boolean,
    val approximateOnly: Boolean,
    val locationEnabled: Boolean,
    val notifications: Boolean,
    /** null до Android 10: там отдельного разрешения нет, фоновая геолокация выдаётся вместе с обычной. */
    val backgroundLocation: Boolean?,
    val batteryExempt: Boolean,
) {
    val canContinue get() = exchangeAndLocation && locationEnabled

    companion object {
        val ALL_GRANTED = PermissionsState(true, false, true, true, true, true)
    }
}

private fun readState(context: Context, powerTools: PowerTools): PermissionsState {
    val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return PermissionsState(
        exchangeAndLocation = RequiredPermissions.allGranted(context, RequiredPermissions.exchange() + RequiredPermissions.location()),
        approximateOnly = coarse && !fine,
        locationEnabled = LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)),
        notifications = PhoneChecks.notificationsEnabled(context),
        backgroundLocation = RequiredPermissions.backgroundLocation().takeIf { it.isNotEmpty() }
            ?.let { RequiredPermissions.allGranted(context, it) },
        batteryExempt = powerTools.isIgnoringBatteryOptimizations(),
    )
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

/** false, если такого экрана настроек на телефоне нет. */
private fun Context.openSettings(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
}

@Composable
private fun PermissionsContent(
    state: PermissionsState,
    essentialAsked: Boolean,
    notificationsAsked: Boolean,
    backgroundAsked: Boolean,
    autostartNotFound: Boolean,
    onRequestEssential: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBackground: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBattery: () -> Unit,
    onAutostart: () -> Unit,
    onDone: () -> Unit,
) {
    AppScreen(
        title = "Разрешения",
        subtitle = "Без первых двух телефон не найдёт группу",
        onBack = onDone,
        bottom = {
            BigButton("Продолжить", onDone, Modifier.fillMaxWidth(), enabled = state.canContinue)
        },
    ) {
        PermissionCard(
            title = "Обмен с группой и геолокация",
            text = when {
                state.approximateOnly -> "Выдана только приблизительная геолокация, по ней трек не записать. " +
                    "В диалоге выберите «Точно»."
                else -> "Устройства поблизости нужны, чтобы находить телефоны группы по Bluetooth и Wi-Fi, " +
                    "точная геолокация нужна для трека и расстояния до участников."
            },
            done = state.exchangeAndLocation,
            required = true,
        ) {
            BigButtonFull("Разрешить", onRequestEssential)
            if (essentialAsked) BigButtonFull("Открыть настройки приложения", onOpenAppSettings, ButtonKind.SECONDARY)
        }
        if (!state.locationEnabled) {
            PermissionCard(
                title = "Геолокация выключена в системе",
                text = "С выключенной геолокацией GPS молчит, даже когда разрешение выдано.",
                done = false,
                required = true,
            ) {
                BigButtonFull("Включить геолокацию", onOpenLocationSettings)
            }
        }
        PermissionCard(
            title = "Уведомления",
            text = if (state.notifications) {
                "Тревога и перекличка придут со звуком, даже когда телефон в кармане."
            } else {
                "Без уведомлений не будет звука тревоги, пока приложение не на экране."
            },
            done = state.notifications,
        ) {
            if (!notificationsAsked && RequiredPermissions.notifications().isNotEmpty()) {
                BigButtonFull("Разрешить", onRequestNotifications)
            } else {
                BigButtonFull("Открыть настройки уведомлений", onOpenNotificationSettings)
            }
        }
        if (state.backgroundLocation != null && state.exchangeAndLocation) {
            PermissionCard(
                title = "Геолокация «Разрешить всегда»",
                text = "Если система выгрузит приложение или телефон перезагрузится, трек продолжит писаться сам. " +
                    "Без этого он встанет, пока вы не откроете приложение.",
                done = state.backgroundLocation,
            ) {
                if (!backgroundAsked) {
                    BigButtonFull("Разрешить всегда", onRequestBackground)
                } else {
                    BigButtonFull("Открыть настройки приложения", onOpenAppSettings, ButtonKind.SECONDARY)
                }
            }
        }
        PermissionCard(
            title = "Работа в фоне",
            text = "Без исключения из оптимизации батареи телефон в кармане засыпает, и группа перестаёт его слышать.",
            done = state.batteryExempt,
        ) {
            BigButtonFull("Разрешить работу в фоне", onBattery)
        }
        // Включён ли автозапуск, система приложению не сообщает, поэтому эта карточка видна всегда.
        PermissionCard(
            title = "Автозапуск",
            text = "На Xiaomi, Honor и Huawei без него приложение не поднимется само после перезагрузки посреди маршрута.",
            done = null,
        ) {
            BigButtonFull("Открыть настройки автозапуска", onAutostart, ButtonKind.SECONDARY)
            if (autostartNotFound) {
                HintText("Страница автозапуска не открылась. В настройках приложения найдите «Автозапуск» или «Запуск в фоне» и включите.")
            }
        }
    }
}

/**
 * Карточка группы разрешений: что это, зачем нужно и кнопки, пока не выдано. [done] равен null, если
 * проверить выдачу нельзя: тогда кнопки видны всегда.
 */
@Composable
private fun PermissionCard(
    title: String,
    text: String,
    done: Boolean?,
    required: Boolean = false,
    actions: @Composable () -> Unit,
) {
    val colors = MaterialTheme.statusColors
    val (container, content) = when {
        done == true -> colors.ok.container to colors.ok.content
        done == false && required -> colors.lost.container to colors.lost.content
        else -> MaterialTheme.colorScheme.surfaceContainerLowest to Color.Unspecified
    }
    InfoCard(container = container) {
        Text(
            if (done == true) "$title: выдано" else title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        if (done != true) actions()
    }
}

@Preview(name = "Разрешения не выданы")
@Composable
private fun PermissionsMissingPreview() {
    PereklichkaTheme {
        PermissionsContent(
            PermissionsState(false, false, false, false, false, false),
            essentialAsked = true, notificationsAsked = false, backgroundAsked = false, autostartNotFound = true,
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(name = "Разрешения выданы", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PermissionsGrantedPreview() {
    PereklichkaTheme {
        PermissionsContent(PermissionsState.ALL_GRANTED, false, false, false, false, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
