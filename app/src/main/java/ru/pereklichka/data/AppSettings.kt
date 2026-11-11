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
 * Настройки с экрана диагностики: адрес сервера, режим демо и список блокировки (его переключатели на экране
 * ещё предстоит добавить).
 *
 * Хранятся в SharedPreferences и живут дольше группы. Значения отдаются потоками, поэтому изменение сразу видят
 * `GroupViewProvider` (порог «Не слышно»), транспорт (блокировка) и `UploadWorker` (адрес).
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val serverUrlState = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty())
    private val demoModeState = MutableStateFlow(prefs.getBoolean(KEY_DEMO_MODE, false))
    private val blockedState = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty().map(::MemberId).toSet(),
    )

    val serverUrl: StateFlow<String> = serverUrlState.asStateFlow()

    /** Режим демо: «Не слышно» через 60 с вместо 120 с. С фейковыми данными из `DataModeModule` он не связан. */
    val demoMode: StateFlow<Boolean> = demoModeState.asStateFlow()

    /**
     * К этим участникам транспорт не подключается и отбрасывает их сообщения. Так на одном столе проверяют
     * пересылку через посредника: A блокирует C, C блокирует A, и данные между ними идут через B.
     */
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
        const val DEFAULT_SERVER_URL = "https://rollcall.vareonix.cc"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEMO_MODE = "demo_mode"
        const val KEY_BLOCKED = "blocked_members"
    }
}
