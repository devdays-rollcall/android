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
 * Запрос разрешений с объяснением. Служба не стартует, пока не выданы разрешения на обмен и геолокацию:
 * на Android 14 запуск службы с типом location без них падает с `SecurityException`.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    // TODO(Василиса): сначала обмен и GPS (RequiredPermissions.exchange и location), потом уведомления, по строке
    //  объяснения на группу; проверить, что геолокация включена в системе; при отказе в уведомлениях объяснить
    //  последствия и предложить настройки. Исключение из оптимизации через PowerTools. docs/team/vasilisa-service-storage.md
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
