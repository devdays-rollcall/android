package ru.pereklichka.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.ui.components.AppScreen
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CardLabel
import ru.pereklichka.ui.components.HintText
import ru.pereklichka.ui.components.InfoCard
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Старт: создать группу или вступить. Первый экран, пока группы нет.
 * Вход в диагностику и разрешения отсюда тоже нужен: без группы их больше неоткуда открыть.
 *
 * Если не выданы разрешения на обмен (`RequiredPermissions.exchange`) или геолокацию (`location`), экран один раз сам
 * открывает экран разрешений, ещё до кнопок. Если человек вернулся, так и не выдав их, сверху остаётся
 * предупреждение с кнопкой: без этих разрешений служба не запустится.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun StartScreen(
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onPermissions: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    var missing by remember { mutableStateOf(inPreview.not() && missingRequired(context)) }
    // Пересчитываем при каждом возврате на экран: человек мог выдать разрешения в настройках.
    LifecycleResumeEffect(Unit) {
        if (!inPreview) missing = missingRequired(context)
        onPauseOrDispose { }
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(missing) {
        if (missing && !askedOnce) {
            askedOnce = true
            onPermissions()
        }
    }
    StartContent(missing, onCreateGroup, onJoinGroup, onPermissions, onDiagnostics)
}

private fun missingRequired(context: android.content.Context): Boolean =
    RequiredPermissions.missing(context, RequiredPermissions.exchange() + RequiredPermissions.location()).isNotEmpty()

@Composable
private fun StartContent(
    permissionsMissing: Boolean,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onPermissions: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    AppScreen(
        title = "Перекличка",
        subtitle = "Группа на связи без интернета",
        bottom = {
            BigButtonFull("Создать группу", onCreateGroup)
            BigButtonFull("Вступить по QR", onJoinGroup, ButtonKind.SECONDARY)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPermissions) { Text("Разрешения") }
                TextButton(onClick = onDiagnostics) { Text("Диагностика") }
            }
        },
    ) {
        if (permissionsMissing) {
            val color = MaterialTheme.statusColors.lost
            InfoCard(container = color.container) {
                Text(
                    "Не выданы разрешения на обмен и геолокацию",
                    style = MaterialTheme.typography.titleMedium,
                    color = color.content,
                )
                Text(
                    "Без них телефон не найдёт группу и не запишет трек.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color.content,
                )
                BigButtonFull("Выдать разрешения", onPermissions, ButtonKind.SECONDARY)
            }
        }
        InfoCard {
            CardLabel("Руководитель")
            Text("Создаёт группу и показывает QR-код", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        InfoCard {
            CardLabel("Участник")
            Text("Сканирует QR-код руководителя", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        HintText("Телефоны группы обмениваются напрямую по Bluetooth и Wi-Fi, интернет и сотовая сеть не нужны.")
    }
}

@Preview(name = "Старт")
@Preview(name = "Старт, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StartPreview() {
    PereklichkaTheme { StartContent(false, {}, {}, {}, {}) }
}

@Preview(name = "Старт без разрешений")
@Composable
private fun StartNoPermissionsPreview() {
    PereklichkaTheme { StartContent(true, {}, {}, {}, {}) }
}
