package ru.pereklichka.service

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Всё, что не даёт системе усыпить приложение на маршруте: partial wake lock, исключение из оптимизации батареи
 * и страница автозапуска у Xiaomi и Honor.
 *
 * Исключение из оптимизации обязательно: в режиме Doze система игнорирует wake lock приложений без него, и неподвижный
 * телефон перестаёт отправлять сводки. Цепочек воскрешения службы здесь нет намеренно: по словам разработчиков, MagicOS
 * отзывает исключение у приложений, которые упорно поднимаются разными путями.
 */
@Singleton
class PowerTools @Inject constructor(@ApplicationContext private val context: Context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Держит процессор включённым, пока работает служба. Повторный вызов ничего не меняет. */
    // Срока у wake lock нет намеренно: служба держит его весь маршрут и отпускает в releaseWakeLock.
    @SuppressLint("WakelockTimeout")
    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    fun isIgnoringBatteryOptimizations(): Boolean = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Системный диалог «Разрешить работу в фоне». Открывать из Activity, когда [isIgnoringBatteryOptimizations] false.
     * Lint предупреждает, что Google Play ограничивает это действие, но без исключения обмен в Doze встаёт.
     */
    @SuppressLint("BatteryLife")
    fun ignoreBatteryOptimizationsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /**
     * Открывает страницу автозапуска производителя или, если её нет, настройки приложения.
     * Возвращает true, если открылась именно страница автозапуска; иначе человеку нужна инструкция словами.
     */
    fun openAutostartSettings(activityContext: Context): Boolean {
        if (AUTOSTART_PAGES.any { activityContext.tryStart(Intent().setComponent(it)) }) return true
        activityContext.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
        return false
    }

    /** false, если страницы у этого производителя нет или она закрыта для сторонних приложений. */
    private fun Context.tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val WAKE_LOCK_TAG = "pereklichka:route"

        /** Известные страницы автозапуска. Их нет в документации производителей, адреса взяты из практики разработчиков. */
        val AUTOSTART_PAGES = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
    }
}
