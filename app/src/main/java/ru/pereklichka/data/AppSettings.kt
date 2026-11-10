package ru.pereklichka.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import ru.pereklichka.domain.model.MemberId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки, которые меняют на экране диагностики: адрес сервера, режим демо, список блокировки.
 *
 * Хранятся в SharedPreferences и живут дольше группы. Значения отдаются потоками, поэтому изменение
 * сразу видят `GroupViewProvider` (порог «Не слышно»), транспорт (блокировка) и `UploadWorker` (адрес).
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val serverUrlState = MutableStateFlow(prefs.getString(KEY_SERVER_URL, null).orEmpty())
    private val demoModeState = MutableStateFlow(prefs.getBoolean(KEY_DEMO_MODE, false))
    private val blockedState = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty().map(::MemberId).toSet(),
    )

    /** Адрес сервера без завершающей косой черты, например `http://192.168.1.20:8080`; пусто, пока не ввели. */
    val serverUrl: StateFlow<String> = serverUrlState.asStateFlow()

    /** Режим демо: «Не слышно» через 60 с вместо 120 с. */
    val demoMode: StateFlow<Boolean> = demoModeState.asStateFlow()

    /** К этим участникам транспорт не подключается и отбрасывает их сообщения (проверка пересылки на одном столе). */
    val blockedMembers: StateFlow<Set<MemberId>> = blockedState.asStateFlow()

    fun setServerUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_SERVER_URL, normalized) }
        serverUrlState.value = normalized
    }

    fun setDemoMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DEMO_MODE, enabled) }
        demoModeState.value = enabled
    }

    fun setBlocked(member: MemberId, blocked: Boolean) {
        val updated = blockedState.updateAndGet { if (blocked) it + member else it - member }
        prefs.edit { putStringSet(KEY_BLOCKED, updated.map { it.value }.toSet()) }
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEMO_MODE = "demo_mode"
        const val KEY_BLOCKED = "blocked_members"
    }
}
