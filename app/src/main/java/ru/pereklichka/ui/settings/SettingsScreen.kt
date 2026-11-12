package ru.pereklichka.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pereklichka.R
import ru.pereklichka.domain.rules.Thresholds
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.CardShape
import ru.pereklichka.ui.components.IconCircle
import ru.pereklichka.ui.components.ListCard
import ru.pereklichka.ui.components.ListRow
import ru.pereklichka.ui.components.PhoneWarning
import ru.pereklichka.ui.components.SectionHeader
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.StatusColor
import ru.pereklichka.ui.theme.statusColors

/**
 * Настройки: готов ли телефон к походу, связь с группой, режим демо и версия. Открывается со старта и из меню
 * группы. Строка с проблемой нажимается целиком и ведёт туда, где проблема исправляется: на экран разрешений
 * ([onPermissions]) или в системные настройки. Проверки перечитываются при каждом возврате на экран.
 *
 * Семь нажатий на «О приложении» открывают экран «Для разработчиков» ([onDev]). После этого в разделе
 * «Приложение» появляется строка с переходом на него, а строки «Рядом» и «Карта похода» тоже ведут туда:
 * подробности о соседях и отправке живут на том экране.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onDev: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var autostartNotFound by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    SettingsContent(
        state = state,
        version = viewModel.version,
        autostartNotFound = autostartNotFound,
        onFix = { fix ->
            when (fix) {
                Fix.PERMISSIONS -> onPermissions()
                Fix.BLUETOOTH_SETTINGS -> context.openSettings(PhoneWarning.BLUETOOTH_OFF.settingsIntent(context))
                Fix.LOCATION_SETTINGS -> context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                Fix.NOTIFICATION_SETTINGS -> context.openSettings(PhoneWarning.NOTIFICATIONS_OFF.settingsIntent(context))
                Fix.SOUND_SETTINGS -> context.openSettings(PhoneWarning.TOTAL_SILENCE.settingsIntent(context))
                Fix.BATTERY -> if (!context.openSettings(viewModel.powerTools.ignoreBatteryOptimizationsIntent())) {
                    context.openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }
                Fix.AUTOSTART -> autostartNotFound = !viewModel.powerTools.openAutostartSettings(context)
            }
        },
        onDemoMode = viewModel::setDemoMode,
        onAbout = {
            if (viewModel.onAboutTap()) {
                Toast.makeText(context, "Открыт экран «Для разработчиков»", Toast.LENGTH_SHORT).show()
            }
        },
        onDev = onDev,
        onBack = onBack,
    )
}

/** false, если такого экрана настроек на телефоне нет. */
private fun Context.openSettings(intent: Intent?): Boolean = try {
    intent != null && run { startActivity(intent); true }
} catch (e: ActivityNotFoundException) {
    false
}

@Composable
private fun SettingsContent(
    state: SettingsState,
    version: String,
    autostartNotFound: Boolean,
    onFix: (Fix) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onDev: () -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = "Настройки", onBack = onBack) {
        state.summary?.let { SummaryCard(it) }

        Section("Проверка телефона") {
            ListCard {
                state.checks.forEachIndexed { index, check ->
                    if (index > 0) RowDivider()
                    CheckRow(
                        check,
                        subtitle = if (check.kind == CheckKind.AUTOSTART && autostartNotFound) {
                            "Отдельной страницы нет, найдите автозапуск в настройках приложения"
                        } else {
                            check.subtitle
                        },
                        onFix = onFix,
                    )
                }
            }
        }

        if (state.hasGroup) {
            Section("Связь с группой") {
                val openDev = onDev.takeIf { state.devUnlocked }
                val chevron: (@Composable RowScope.() -> Unit)? = if (openDev != null) ({ Chevron() }) else null
                ListCard {
                    ListRow(
                        title = nearbyTitle(state.nearby.size),
                        subtitle = nearbySubtitle(state.nearby, state.lastMessageAgoMs),
                        onClick = openDev,
                        leading = { PrimaryIcon(R.drawable.sym_cell_tower) },
                        trailing = chevron,
                    )
                    RowDivider()
                    ListRow(
                        title = "Карта похода",
                        subtitle = mapSubtitle(state.pointsSent, state.pointsWaiting, state.serverSet, state.uploadError),
                        subtitleColor = if (state.uploadError != null && state.serverSet) {
                            MaterialTheme.statusColors.lost.content
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = openDev,
                        leading = { PrimaryIcon(R.drawable.sym_cloud_upload) },
                        trailing = chevron,
                    )
                }
            }
        }

        Section("Приложение") {
            ListCard {
                ListRow(
                    title = "Режим демо",
                    subtitle = "«Не слышно» через ${minutes(Thresholds.DEMO.lostAfterMs)} мин " +
                        "вместо ${minutes(Thresholds().lostAfterMs)}",
                    modifier = Modifier.toggleable(state.demoMode, role = Role.Switch, onValueChange = onDemoMode),
                    leading = { GrayIcon(R.drawable.sym_timer) },
                    trailing = { Switch(checked = state.demoMode, onCheckedChange = null) },
                )
                RowDivider()
                ListRow(
                    title = "О приложении",
                    subtitle = version,
                    onClick = onAbout,
                    leading = { GrayIcon(R.drawable.sym_info) },
                )
                if (state.devUnlocked) {
                    RowDivider()
                    ListRow(
                        title = "Для разработчиков",
                        subtitle = "Сервер, соседи и блокировка, счётчики",
                        onClick = onDev,
                        leading = { GrayIcon(R.drawable.sym_code) },
                        trailing = { Chevron() },
                    )
                }
            }
            if (!state.devUnlocked) {
                Text(
                    "Для разработчиков: ${SettingsViewModel.DEV_TAPS} нажатий на «О приложении»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        }
    }
}

private fun minutes(ms: Long) = ms / 60_000

/** Подзаголовок и всё, что под ним, с меньшим отступом, чем между разделами. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
    }
}

/** Цветная карточка итога: зелёная, жёлтая или красная. */
@Composable
private fun SummaryCard(summary: PhoneSummary) {
    val colors = summary.level.colors()
    Surface(Modifier.fillMaxWidth(), shape = CardShape, color = colors.container, contentColor = colors.content) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SymIcon(if (summary.level == CheckLevel.OK) R.drawable.sym_check_circle_fill else R.drawable.sym_error_fill, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(summary.title, style = MaterialTheme.typography.titleMedium)
                Text(summary.subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CheckRow(check: PhoneCheck, subtitle: String, onFix: (Fix) -> Unit) {
    val colors = check.level.colors()
    val fix = check.fix
    ListRow(
        title = check.kind.title,
        subtitle = subtitle,
        subtitleColor = if (check.level == CheckLevel.WARNING || check.level == CheckLevel.REQUIRED) {
            colors.content
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = fix?.let { { onFix(it) } },
        leading = { IconCircle(check.kind.icon(), colors.container, colors.content) },
        trailing = {
            when {
                check.level == CheckLevel.OK ->
                    SymIcon(R.drawable.sym_check_circle_fill, tint = MaterialTheme.statusColors.ok.content)
                check.level == CheckLevel.MANUAL -> Chevron()
                fix != null -> Text(
                    "Исправить",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                )
                else -> SymIcon(R.drawable.sym_warning_fill, tint = colors.content)
            }
        },
    )
}

private fun CheckKind.icon(): Int = when (this) {
    CheckKind.BLUETOOTH -> R.drawable.sym_bluetooth_fill
    CheckKind.LOCATION -> R.drawable.sym_my_location_fill
    CheckKind.NOTIFICATIONS -> R.drawable.sym_notifications_fill
    CheckKind.DO_NOT_DISTURB -> R.drawable.sym_do_not_disturb_on_fill
    CheckKind.BACKGROUND -> R.drawable.sym_battery_saver_fill
    CheckKind.AUTOSTART -> R.drawable.sym_restart_alt
}

/** Цвета строки и карточки: норма зелёная, проблема жёлтая, нет обязательного разрешения красная, ручная проверка серая. */
@Composable
private fun CheckLevel.colors(): StatusColor = when (this) {
    CheckLevel.OK -> MaterialTheme.statusColors.ok
    CheckLevel.WARNING -> MaterialTheme.statusColors.away
    CheckLevel.REQUIRED -> MaterialTheme.statusColors.lost
    CheckLevel.MANUAL -> MaterialTheme.colorScheme.run { StatusColor(surfaceContainerHigh, onSurfaceVariant, onSurfaceVariant) }
}

@Composable
private fun PrimaryIcon(icon: Int) =
    IconCircle(icon, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)

@Composable
private fun GrayIcon(icon: Int) =
    IconCircle(icon, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Chevron() = SymIcon(R.drawable.sym_chevron_right, tint = MaterialTheme.colorScheme.onSurface)

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Preview(heightDp = 1200)
@Composable
private fun SettingsPreview() {
    val readings = PhoneReadings(
        exchangeGranted = true, fineLocation = true, coarseLocation = true, locationEnabled = true, backgroundLocation = true,
        bluetoothEnabled = true, playServices = true, notifications = true, totalSilence = true, batteryExempt = true,
    )
    val checks = phoneChecks(readings)
    PereklichkaTheme {
        SettingsContent(
            state = SettingsState(
                checks = checks,
                summary = phoneSummary(checks),
                hasGroup = true,
                nearby = listOf("Даниил", "Ольга"),
                lastMessageAgoMs = 3_000,
                pointsSent = 1_240,
                pointsWaiting = 12,
                serverSet = true,
            ),
            version = "Версия 1.0 · сборка 42",
            autostartNotFound = false,
            onFix = {},
            onDemoMode = {},
            onAbout = {},
            onDev = {},
            onBack = {},
        )
    }
}
