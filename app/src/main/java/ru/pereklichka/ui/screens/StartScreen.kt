package ru.pereklichka.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.pereklichka.R
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.ui.components.BigButtonFull
import ru.pereklichka.ui.components.ButtonKind
import ru.pereklichka.ui.components.CustomTopScreen
import ru.pereklichka.ui.components.SymIcon
import ru.pereklichka.ui.components.plural
import ru.pereklichka.ui.theme.PereklichkaTheme
import ru.pereklichka.ui.theme.statusColors

/**
 * Старт: создать группу или вступить по QR-коду. Первый экран, пока группы нет. Шестерёнка справа вверху
 * открывает настройки: без группы их больше неоткуда открыть.
 *
 * Строка под кнопками показывает готовность телефона. Если выданы разрешения на обмен
 * (`RequiredPermissions.exchange`) и геолокацию (`location`), в ней «Телефон готов к походу», иначе нажимаемая
 * строка «Нужно выдать N разрешения», которая ведёт на экран разрешений. N считается так же, как обязательные пункты
 * на том экране: обмен с телефонами группы и геолокация.
 *
 * Если чего-то из этого не хватает, экран один раз сам открывает экран разрешений, ещё до кнопок. Разрешения
 * перечитываются при каждом возврате на экран: человек мог выдать их в настройках телефона.
 */
@Composable
fun StartScreen(
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    var missing by remember { mutableIntStateOf(if (inPreview) 0 else missingRequired(context)) }
    LifecycleResumeEffect(Unit) {
        if (!inPreview) missing = missingRequired(context)
        onPauseOrDispose { }
    }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(missing) {
        if (missing > 0 && !askedOnce) {
            askedOnce = true
            onPermissions()
        }
    }
    StartContent(missing, onCreateGroup, onJoinGroup, onPermissions, onSettings)
}

/**
 * Сколько обязательных пунктов экрана разрешений не выдано: обмен с телефонами группы и геолокация. До Android 12
 * отдельного разрешения на обмен нет, Nearby работает по геолокации, и пункт остаётся один.
 */
private fun missingRequired(context: Context): Int {
    val location = RequiredPermissions.location()
    val exchangeOnly = RequiredPermissions.exchange() - location.toSet()
    return listOf(exchangeOnly, location).count { it.isNotEmpty() && !RequiredPermissions.allGranted(context, it) }
}

@Composable
private fun StartContent(
    missingPermissions: Int,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onPermissions: () -> Unit,
    onSettings: () -> Unit,
) {
    CustomTopScreen(
        top = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onSettings) {
                    SymIcon(R.drawable.sym_settings, contentDescription = "Настройки")
                }
            }
        },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        contentSpacing = 16.dp,
        bottom = {
            BigButtonFull("Создать группу", onCreateGroup, icon = R.drawable.sym_add, large = true)
            BigButtonFull("Вступить по QR-коду", onJoinGroup, ButtonKind.SECONDARY, icon = R.drawable.sym_qr_code_scanner, large = true)
            Readiness(missingPermissions, onPermissions)
        },
    ) {
        Hero()
        Spacer(Modifier.height(4.dp))
        HowItWorks(R.drawable.sym_qr_code_2, "Руководитель создаёт группу", "и показывает QR-код участникам")
        HowItWorks(R.drawable.sym_bluetooth, "Телефоны находят друг друга сами", "по Bluetooth и Wi-Fi, интернет не нужен")
        HowItWorks(R.drawable.sym_notifications_active, "Отставшего видно сразу", "и тревога дойдёт до всей группы")
    }
}

/** Шапка с названием приложения и горами на светло-зелёном фоне. */
@Composable
private fun Hero() {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.background))),
    ) {
        Mountains(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(52.dp).background(scheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    SymIcon(R.drawable.sym_groups_fill, tint = scheme.onPrimary, size = 30.dp)
                }
                Text(
                    "Перекличка",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }
            Text(
                "Группа на связи в горах,\nгде нет сотовой сети",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Силуэт гор из макета: дальний хребет со снежной вершиной и ближний пологий. Координаты в сетке 379 на 150. */
@Composable
private fun Mountains(modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val far = primary.copy(alpha = 0.9f)
    val near = lerp(primary, MaterialTheme.colorScheme.primaryContainer, 0.15f)
    val snow = lerp(primary, Color.White, 0.8f)
    Canvas(modifier) {
        val sx = size.width / 379f
        val sy = size.height / 150f
        fun polygon(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { index, (x, y) ->
                val offset = Offset(x * sx, y * sy)
                if (index == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
            }
            close()
        }
        drawPath(
            polygon(0f to 150f, 70f to 70f, 110f to 105f, 175f to 30f, 235f to 95f, 270f to 70f, 340f to 120f, 379f to 90f, 379f to 150f),
            far,
        )
        drawPath(polygon(175f to 30f, 195f to 52f, 182f to 50f, 168f to 60f, 160f to 48f), snow)
        drawPath(polygon(0f to 150f, 50f to 120f, 120f to 140f, 200f to 115f, 280f to 140f, 379f to 125f, 379f to 150f), near)
    }
}

/** Шаг «как это работает»: иконка в круге, заголовок и пояснение. */
@Composable
private fun HowItWorks(@DrawableRes icon: Int, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(icon, tint = MaterialTheme.colorScheme.primary)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** «Телефон готов к походу» или нажимаемое «Нужно выдать N разрешения». */
@Composable
private fun Readiness(missing: Int, onPermissions: () -> Unit) {
    val ok = missing == 0
    val color = if (ok) MaterialTheme.statusColors.ok.content else MaterialTheme.statusColors.lost.content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .then(if (ok) Modifier else Modifier.clickable(role = Role.Button, onClick = onPermissions))
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(if (ok) R.drawable.sym_check_circle_fill else R.drawable.sym_warning_fill, tint = color, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (ok) "Телефон готов к походу" else "Нужно выдать $missing ${plural(missing, "разрешение", "разрешения", "разрешений")}",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        if (!ok) SymIcon(R.drawable.sym_chevron_right, tint = color, size = 18.dp)
    }
}

@Preview(name = "Старт")
@Preview(name = "Старт, тёмная", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StartPreview() {
    PereklichkaTheme { StartContent(0, {}, {}, {}, {}) }
}

@Preview(name = "Старт без разрешений")
@Composable
private fun StartNoPermissionsPreview() {
    PereklichkaTheme { StartContent(2, {}, {}, {}, {}) }
}
