package ru.pereklichka.permissions

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import ru.pereklichka.ui.screens.StubButton
import ru.pereklichka.ui.screens.StubScreen

/**
 * Экран разрешений. Должен спрашивать разрешения на обмен, геолокацию и уведомления и объяснять, зачем каждое;
 * сейчас он только показывает, чего не хватает, и открывает настройки приложения.
 * Открывается со стартового экрана, [onDone] возвращает назад.
 *
 * Служба берёт только те типы, на которые выданы разрешения (Android 14 роняет запуск типа без своего
 * разрешения с `SecurityException`), а без разрешений и на обмен, и на геолокацию не стартует вовсе:
 * `RouteService.start` вернёт false.
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    // TODO(Василиса): сначала обмен и GPS (RequiredPermissions.exchange и location), потом уведомления, по строке
    //  объяснения на группу; проверить, что геолокация включена в системе; при отказе в уведомлениях объяснить
    //  последствия и предложить настройки. Исключение из оптимизации через PowerTools.
    //  docs/team/vasilisa-service-storage.md
    val context = LocalContext.current
    val missing = RequiredPermissions.missing(
        context,
        RequiredPermissions.exchange() + RequiredPermissions.location() + RequiredPermissions.notifications(),
    )
    StubScreen("Разрешения") {
        Text(if (missing.isEmpty()) "Все разрешения выданы" else "Не выданы:\n" + missing.joinToString("\n"))
        StubButton("Открыть настройки приложения") {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
            )
        }
        StubButton("Продолжить", onDone)
    }
}
