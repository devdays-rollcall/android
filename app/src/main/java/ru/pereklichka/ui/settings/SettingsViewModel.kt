package ru.pereklichka.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.notify.PhoneChecks
import ru.pereklichka.permissions.RequiredPermissions
import ru.pereklichka.service.PowerTools
import ru.pereklichka.status.GroupViewProvider
import ru.pereklichka.transport.RadioCheck
import ru.pereklichka.upload.UploadStatus
import javax.inject.Inject

/** Всё, что показывает [SettingsScreen]. */
data class SettingsState(
    val checks: List<PhoneCheck> = emptyList(),
    val summary: PhoneSummary? = null,
    /** Без группы раздел «Связь с группой» не показывается: показывать в нём нечего. */
    val hasGroup: Boolean = false,
    /** Имена тех, кого телефон слышит напрямую сейчас, по алфавиту. */
    val nearby: List<String> = emptyList(),
    /** Сколько миллисекунд назад пришло последнее сообщение от кого-нибудь из группы; null, если не приходило. */
    val lastMessageAgoMs: Long? = null,
    val pointsSent: Int = 0,
    val pointsWaiting: Int = 0,
    val serverSet: Boolean = false,
    val uploadError: String? = null,
    val demoMode: Boolean = false,
    val devUnlocked: Boolean = false,
)

/** Точки в базе для строки «Карта похода»: всего, ждут отправки, отклонены сервером. */
private data class PointCounts(val total: Int, val waiting: Int, val rejected: Int)

/**
 * Данные для [SettingsScreen]: проверки телефона, соседи, отправка точек и настройки приложения. Проверки
 * и соседи перечитываются раз в 2 секунды, а [refresh] перечитывает их сразу, когда человек вернулся из системных
 * настроек. Здесь же считаются нажатия на «О приложении», которые открывают экран «Для разработчиков».
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    groupRepository: GroupRepository,
    db: AppDatabase,
    private val radioCheck: RadioCheck,
    val powerTools: PowerTools,
    private val settings: AppSettings,
    uploadStatus: UploadStatus,
    private val presence: PresenceTracker,
    viewProvider: GroupViewProvider,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    // Новое значение refreshes перезапускает цикл, и проверки читаются сразу, не дожидаясь очередного тика.
    private val ticks = refreshes.flatMapLatest {
        flow {
            while (true) {
                emit(phoneChecks(readPhone()) to presence.heardAgo())
                delay(REFRESH_MS)
            }
        }
    }

    private val points = combine(
        db.locationDao().observeCount(),
        db.locationDao().observeUnsentCount(),
        db.locationDao().observeRejectedCount(),
        ::PointCounts,
    )

    private val upload = combine(settings.serverUrl, uploadStatus.lastError, ::Pair)

    private val app = combine(settings.demoMode, settings.devOptionsUnlocked, ::Pair)

    val state: StateFlow<SettingsState> = combine(
        ticks,
        combine(groupRepository.activeGroup, viewProvider.view, ::Pair),
        points,
        upload,
        app,
    ) { (checks, heard), (group, view), counts, (serverUrl, error), (demo, unlocked) ->
        val names = view?.members?.associate { it.id to it.name }.orEmpty()
        SettingsState(
            checks = checks,
            summary = phoneSummary(checks),
            hasGroup = group != null,
            nearby = heard.filterValues { PresenceTracker.isHeardNow(it) }.keys
                .map { names[it] ?: it.value.take(SHORT_ID) }
                .sorted(),
            lastMessageAgoMs = heard.values.minOrNull(),
            pointsSent = counts.total - counts.waiting - counts.rejected,
            pointsWaiting = counts.waiting,
            serverSet = serverUrl.isNotBlank(),
            uploadError = error,
            demoMode = demo,
            devUnlocked = unlocked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsState())

    /** «Версия 0.1 · сборка 1» из манифеста установленного приложения. */
    val version: String = readVersion()

    private var aboutTaps = 0

    /** Перечитать проверки сейчас: человек вернулся на экран, возможно, из системных настроек. */
    fun refresh() = refreshes.update { it + 1 }

    fun setDemoMode(enabled: Boolean) = settings.setDemoMode(enabled)

    /**
     * Нажатие на «О приложении». Седьмое подряд открывает экран «Для разработчиков» насовсем; true, если открыло
     * именно это нажатие.
     */
    fun onAboutTap(): Boolean {
        if (settings.devOptionsUnlocked.value) return false
        aboutTaps++
        if (aboutTaps < DEV_TAPS) return false
        settings.unlockDevOptions()
        return true
    }

    private fun readPhone(): PhoneReadings {
        val background = RequiredPermissions.backgroundLocation()
        return PhoneReadings(
            exchangeGranted = RequiredPermissions.allGranted(context, RequiredPermissions.exchange()),
            fineLocation = RequiredPermissions.allGranted(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
            coarseLocation = RequiredPermissions.allGranted(context, listOf(Manifest.permission.ACCESS_COARSE_LOCATION)),
            locationEnabled = context.getSystemService(LocationManager::class.java)
                ?.let(LocationManagerCompat::isLocationEnabled) == true,
            backgroundLocation = background.takeIf { it.isNotEmpty() }?.let { RequiredPermissions.allGranted(context, it) },
            bluetoothEnabled = radioCheck.isBluetoothEnabled(),
            playServices = radioCheck.isPlayServicesAvailable(),
            notifications = PhoneChecks.notificationsEnabled(context),
            totalSilence = PhoneChecks.totalSilence(context),
            batteryExempt = powerTools.isIgnoringBatteryOptimizations(),
        )
    }

    private fun readVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return "Версия ${info.versionName} · сборка ${PackageInfoCompat.getLongVersionCode(info)}"
    }

    companion object {
        /** Сколько нажатий на «О приложении» открывают экран «Для разработчиков». */
        const val DEV_TAPS = 7
        private const val SHORT_ID = 8
        private const val REFRESH_MS = 2_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
