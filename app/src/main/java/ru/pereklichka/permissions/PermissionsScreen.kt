package ru.pereklichka.permissions

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.pereklichka.R
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.service.PowerTools
import ru.pereklichka.ui.components.BackButton
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.CustomTopScreen
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.ProgressLine
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Экран разрешений «Подготовим телефон к походу»: чек-лист со счётчиком «N из M». Стартовый экран открывает его сам,
 * если не выданы обмен или геолокация, а ещё его открывают строка готовности на старте, меню группы
 * и предупреждения на экранах группы.
 *
 * Сверху обязательные пункты: «Устройства поблизости» (с Android 12; на старых версиях Nearby хватает геолокации), «Точная
 * геолокация» и, если геолокация выключена в системе, пункт с кнопкой «Включить». «Продолжить» доступна, когда они
 * выполнены: без них телефон не найдёт группу и не запишет трек. Ниже «Желательно»: уведомления, «Разрешить всегда»,
 * работа в фоне и автозапуск. Без них продолжить можно. Автозапуск в счётчик не входит: включён ли он, система
 * приложению не сообщает.
 *
 * Если система больше не показывает диалог разрешения (человек отказал дважды или выбрал «Больше не спрашивать»),
 * кнопка пункта называется «Настройки» и открывает настройки приложения. Состояние перечитывается при каждом
 * возврате на экран. Камеру экран не спрашивает, её спрашивает экран вступления.
 *
 * «Разрешить всегда» спрашивается отдельным запросом, а не вместе с обычной геолокацией: с Android 11 система
 * по этому запросу открывает страницу настроек, и только после выданной точной геолокации.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val inPreview = LocalInspectionMode.current
    val powerTools = remember(context) { PowerTools(context.applicationContext) }
    var state by remember { mutableStateOf(if (inPreview) PermissionsState.ALL_GRANTED else readState(context, powerTools)) }
    LifecycleResumeEffect(Unit) {
        if (!inPreview) state = readState(context, powerTools)
        onPauseOrDispose { }
    }
    var nearbyBlocked by rememberSaveable { mutableStateOf(false) }
    var locationBlocked by rememberSaveable { mutableStateOf(false) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var backgroundAsked by rememberSaveable { mutableStateOf(false) }
    var autostartNotFound by rememberSaveable { mutableStateOf(false) }

    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        nearbyBlocked = blocked(activity, it)
        state = readState(context, powerTools)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationBlocked = blocked(activity, it)
        state = readState(context, powerTools)
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsBlocked = blocked(activity, it)
        state = readState(context, powerTools)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        backgroundAsked = true
        state = readState(context, powerTools)
    }

    PermissionsContent(
        state = state,
        actions = PermissionActions(
            nearbyBlocked = nearbyBlocked,
            locationBlocked = locationBlocked,
            notificationsBlocked = notificationsBlocked,
            backgroundAsked = backgroundAsked,
            autostartNotFound = autostartNotFound,
            onRequestNearby = { nearbyLauncher.launch(nearbyOnly().toTypedArray()) },
            onRequestLocation = { locationLauncher.launch(RequiredPermissions.location().toTypedArray()) },
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
        ),
        onDone = onDone,
    )
}

/** Разрешения обмена без геолокации: Bluetooth и Wi-Fi рядом. До Android 12 список пуст. */
private fun nearbyOnly(): List<String> = RequiredPermissions.exchange() - RequiredPermissions.location().toSet()

/**
 * true, если после отказа система больше не покажет диалог: ни для одного отказанного разрешения она не предлагает
 * объяснить, зачем оно нужно. Без активности считаем так же, чтобы кнопка вела в настройки.
 */
private fun blocked(activity: Activity?, result: Map<String, Boolean>): Boolean {
    val denied = result.filterValues { !it }.keys
    if (denied.isEmpty()) return false
    return activity == null || denied.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

/** Что выдано на этот момент. */
private data class PermissionsState(
    /** null до Android 12: отдельных разрешений на обмен там нет. */
    val nearby: Boolean?,
    val fineLocation: Boolean,
    val approximateOnly: Boolean,
    val exchangeAndLocation: Boolean,
    val locationEnabled: Boolean,
    val notifications: Boolean,
    /** null до Android 10: там отдельного разрешения нет, фоновая геолокация выдаётся вместе с обычной. */
    val backgroundLocation: Boolean?,
    val batteryExempt: Boolean,
) {
    val canContinue get() = exchangeAndLocation && locationEnabled

    /** Выполненные и все пункты для счётчика. Пункт о выключенной геолокации считается, только пока он виден. */
    val progress: Pair<Int, Int>
        get() {
            val items = listOfNotNull(
                nearby,
                fineLocation,
                false.takeIf { !locationEnabled },
                notifications,
                backgroundLocation,
                batteryExempt,
            )
            return items.count { it } to items.size
        }

    companion object {
        val ALL_GRANTED = PermissionsState(true, true, false, true, true, true, true, true)
    }
}

private fun readState(context: Context, powerTools: PowerTools): PermissionsState {
    val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return PermissionsState(
        nearby = nearbyOnly().takeIf { it.isNotEmpty() }?.let { RequiredPermissions.allGranted(context, it) },
        fineLocation = fine,
        approximateOnly = coarse && !fine,
        exchangeAndLocation = RequiredPermissions.allGranted(context, RequiredPermissions.exchange() + RequiredPermissions.location()),
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

/** Что известно о прошлых запросах и что делают кнопки пунктов. */
private class PermissionActions(
    val nearbyBlocked: Boolean,
    val locationBlocked: Boolean,
    val notificationsBlocked: Boolean,
    val backgroundAsked: Boolean,
    val autostartNotFound: Boolean,
    val onRequestNearby: () -> Unit,
    val onRequestLocation: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onRequestBackground: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onBattery: () -> Unit,
    val onAutostart: () -> Unit,
)

@Composable
private fun PermissionsContent(state: PermissionsState, actions: PermissionActions, onDone: () -> Unit) {
    val (done, total) = state.progress
    val requiredCount = listOfNotNull(state.nearby, true, if (state.locationEnabled) null else true).size
    CustomTopScreen(
        top = {
            Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) { BackButton(onDone) }
        },
        bottom = { BigButtonFull("Продолжить", onDone, large = true, enabled = state.canContinue) },
    ) {
        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
            Text(
                "Подготовим телефон\nк походу",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Без ${requiredWords(requiredCount)} телефон не найдёт группу. Остальные помогут не пропустить тревогу.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProgressLine(done.toFloat() / total, MaterialTheme.statusColors.ok.content, modifier = Modifier.weight(1f))
                Text("$done из $total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RequiredList(state, actions)
        SectionHeader("Желательно")
        DesirableList(state, actions)
    }
}

/** «первых двух пунктов» и т. п.: обязательных пунктов от одного до трёх. */
private fun requiredWords(count: Int): String = when (count) {
    1 -> "первого пункта"
    2 -> "первых двух пунктов"
    else -> "первых трёх пунктов"
}

@Composable
private fun RequiredList(state: PermissionsState, actions: PermissionActions) {
    val rows = buildList<@Composable () -> Unit> {
        if (state.nearby != null) {
            add {
                PermissionRow(
                    R.drawable.sym_bluetooth_searching, "Устройства поблизости", "Находить телефоны группы",
                    done = state.nearby, required = true,
                ) {
                    if (actions.nearbyBlocked) CompactButton("Настройки", actions.onOpenAppSettings)
                    else CompactButton("Разрешить", actions.onRequestNearby)
                }
            }
        }
        add {
            PermissionRow(
                if (state.fineLocation) R.drawable.sym_my_location_fill else R.drawable.sym_my_location,
                "Точная геолокация",
                if (state.approximateOnly) "Выдана только приблизительная. В диалоге выберите «Точно»" else "Трек и расстояние до участников",
                done = state.fineLocation, required = true,
                warning = state.approximateOnly,
            ) {
                if (actions.locationBlocked) CompactButton("Настройки", actions.onOpenAppSettings)
                else CompactButton("Разрешить", actions.onRequestLocation)
            }
        }
        if (!state.locationEnabled) {
            add {
                PermissionRow(
                    R.drawable.sym_location_off, "Геолокация выключена", "GPS молчит, даже когда разрешение выдано",
                    done = false, required = true,
                ) {
                    CompactButton("Включить", actions.onOpenLocationSettings)
                }
            }
        }
    }
    ListCard(rows)
}

@Composable
private fun DesirableList(state: PermissionsState, actions: PermissionActions) {
    ListCard {
        PermissionRow(R.drawable.sym_notifications, "Уведомления", "Звук тревоги в кармане", done = state.notifications) {
            if (!actions.notificationsBlocked && RequiredPermissions.notifications().isNotEmpty()) {
                CompactButton("Разрешить", actions.onRequestNotifications)
            } else {
                CompactButton("Настройки", actions.onOpenNotificationSettings)
            }
        }
        if (state.backgroundLocation != null) {
            RowDivider()
            PermissionRow(
                R.drawable.sym_location_on,
                "Геолокация всегда",
                if (state.fineLocation) "Трек не встанет после перезапуска" else "Сначала выдайте точную геолокацию",
                done = state.backgroundLocation,
            ) {
                when {
                    !state.fineLocation -> Unit
                    actions.backgroundAsked -> CompactButton("Настройки", actions.onOpenAppSettings)
                    else -> CompactButton("Разрешить", actions.onRequestBackground)
                }
            }
        }
        RowDivider()
        PermissionRow(R.drawable.sym_battery_saver, "Работа в фоне", "Телефон не уснёт в кармане", done = state.batteryExempt) {
            CompactButton("Разрешить", actions.onBattery)
        }
        RowDivider()
        PermissionRow(
            R.drawable.sym_restart_alt,
            "Автозапуск",
            if (actions.autostartNotFound) {
                "Страница не открылась. В настройках приложения найдите «Автозапуск» или «Запуск в фоне» и включите"
            } else {
                "Xiaomi, Honor и Huawei"
            },
            done = null,
            warning = actions.autostartNotFound,
        ) {
            TextButton(onClick = actions.onAutostart, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Открыть", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/**
 * Пункт чек-листа: иконка в круге, название, пояснение и справа галочка или кнопка [action]. Зелёный круг у
 * выполненного пункта, красноватый у невыполненного обязательного, серый у остальных. [done] равен null, если
 * проверить выдачу нельзя: тогда кнопка видна всегда. [warning] красит пояснение, когда в нём предупреждение.
 */
@Composable
private fun PermissionRow(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    done: Boolean?,
    required: Boolean = false,
    warning: Boolean = false,
    action: @Composable () -> Unit,
) {
    val status = MaterialTheme.statusColors
    val (container, content) = when {
        done == true -> status.ok.container to status.ok.content
        required -> status.lost.container to status.lost.content
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListRow(
        title = title,
        subtitle = text,
        subtitleColor = if (warning) status.lost.content else MaterialTheme.colorScheme.onSurfaceVariant,
        leading = { IconCircle(icon, container, content) },
        trailing = {
            if (done == true) {
                SymIcon(R.drawable.sym_check_circle_fill, contentDescription = "Выдано", tint = status.ok.content)
            } else {
                action()
            }
        },
    )
}

/** Компактная тональная кнопка 40 dp в строке чек-листа. Зона нажатия 48 dp добавляется Material сама. */
@Composable
private fun CompactButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

private val PreviewActions = PermissionActions(
    nearbyBlocked = false, locationBlocked = false, notificationsBlocked = false, backgroundAsked = false,
    autostartNotFound = false, {}, {}, {}, {}, {}, {}, {}, {}, {},
)

@Preview(name = "Разрешения: 2 из 5")
@Composable
private fun PermissionsPartlyPreview() {
    PereklichkaTheme {
        PermissionsContent(
            PermissionsState(
                nearby = true, fineLocation = true, approximateOnly = false, exchangeAndLocation = true, locationEnabled = true,
                notifications = false, backgroundLocation = false, batteryExempt = false,
            ),
            PreviewActions,
        ) {}
    }
}

@Preview(name = "Разрешения не выданы")
@Composable
private fun PermissionsMissingPreview() {
    PereklichkaTheme {
        PermissionsContent(
            PermissionsState(
                nearby = false, fineLocation = false, approximateOnly = true, exchangeAndLocation = false, locationEnabled = false,
                notifications = false, backgroundLocation = false, batteryExempt = false,
            ),
            PermissionActions(
                nearbyBlocked = true, locationBlocked = false, notificationsBlocked = false, backgroundAsked = false,
                autostartNotFound = true, {}, {}, {}, {}, {}, {}, {}, {}, {},
            ),
        ) {}
    }
}

@Preview(name = "Разрешения выданы", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PermissionsGrantedPreview() {
    PereklichkaTheme { PermissionsContent(PermissionsState.ALL_GRANTED, PreviewActions) {} }
}
