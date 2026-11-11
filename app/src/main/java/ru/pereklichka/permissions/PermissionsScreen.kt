package ru.pereklichka.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.EntryPointAccessors
import ru.pereklichka.service.PowerTools
import ru.pereklichka.ui.screens.StubButton
import ru.pereklichka.ui.screens.StubScreen
import kotlin.jvm.java

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationManager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    var locationEnabled by remember {
        mutableStateOf(LocationManagerCompat.isLocationEnabled(locationManager))
    }
    var essentialGranted by remember { mutableStateOf(
        RequiredPermissions.allGranted(
            context,
            RequiredPermissions.exchange() + RequiredPermissions.location()
        )
    ) }

    var notificationsGranted by remember { mutableStateOf(
        RequiredPermissions.allGranted(
            context,
            RequiredPermissions.notifications()
        )
    ) }

    val powerTools = remember(context) { PowerTools(context.applicationContext) }

    var batteryOptimized by remember {
        mutableStateOf(!powerTools.isIgnoringBatteryOptimizations())
    }

    var onlyApproximate by remember { mutableStateOf(isOnlyApproximate(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        essentialGranted = RequiredPermissions.allGranted(
            context,
            RequiredPermissions.exchange() + RequiredPermissions.location()
        )
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        notificationsGranted = RequiredPermissions.allGranted(
            context,
            RequiredPermissions.notifications()
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
                essentialGranted = RequiredPermissions.allGranted(
                    context, RequiredPermissions.exchange() + RequiredPermissions.location(),
                )
                notificationsGranted = RequiredPermissions.allGranted(
                    context, RequiredPermissions.notifications(),
                )
                onlyApproximate = isOnlyApproximate(context)
                batteryOptimized = !powerTools.isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StubScreen("Разрешения") {
        when {
            // 1. Системная геолокация выключена — самое первое, что видит человек
            !locationEnabled -> {
                Text("Геолокация выключена в системе. Включите GPS в настройках системы.")
                StubButton("Открыть настройки местоположения") {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }

            // 2. GPS включён, но нет runtime-разрешений
            !essentialGranted -> {
                Text("Нужны разрешения для обмена данными и геолокация.")
                StubButton("Выдать разрешения") {
                    launcher.launch(
                        (RequiredPermissions.exchange() + RequiredPermissions.location()).toTypedArray()
                    )
                }
            }

            // 3. Обязательные выданы — опциональные
            else -> {
                Text("Обмен и геолокация выданы.")

                // точность (Android 12+): только приблизительная
                if (onlyApproximate) {
                    Text("Выдана только приблизительная геолокация — для корректной работы необходимо точное местоположение.")
                    StubButton("Выдать точную геолокацию") {
                        launcher.launch(RequiredPermissions.location().toTypedArray())
                    }
                    StubButton("Открыть настройки приложения") {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri())
                        )
                    }
                }

                // уведомления
                if (notificationsGranted) {
                    Text("Уведомления выданы — звук тревоги будет работать.")
                } else {
                    Text("Без уведомлений не будет звука тревоги.")
                    if (RequiredPermissions.notifications().isNotEmpty()) {
                        StubButton("Выдать уведомления") {
                            notificationsLauncher.launch(RequiredPermissions.notifications().toTypedArray())
                        }
                    }
                    StubButton("Открыть настройки уведомлений") {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }

                // батарея
                if (batteryOptimized) {
                    Text("Чтобы телефон не усыплял приложение в походе, исключите его из оптимизации батареи.")
                    StubButton("Исключить из оптимизации батареи") {
                        try {
                            context.startActivity(powerTools.ignoreBatteryOptimizationsIntent())
                        } catch (e: ActivityNotFoundException) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:${context.packageName}".toUri())
                            )
                        }
                    }
                } else {
                    Text("Исключение из оптимизации батареи выдано.")
                }

                StubButton("Продолжить", onDone)
            }
        }
    }

}
private fun isOnlyApproximate(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return coarse && !fine
}