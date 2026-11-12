package ru.pereklichka.upload

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Итог отправки на сервер: время последнего ответа 202 и последняя ошибка. Пишет их [UploadWorker], показывают
 * экраны «Настройки» и «Для разработчиков». Время ответа хранится в SharedPreferences: по нему же [UploadWorker]
 * решает, пора ли слать heartbeat, и после перезапуска процесса оно должно сохраниться. Ошибка живёт только
 * в памяти, потому что следующий запуск отправки её всё равно перепишет.
 */
@Singleton
class UploadStatus @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lastErrorState = MutableStateFlow<String?>(null)
    private val lastAcceptedState = MutableStateFlow(prefs.getLong(KEY_LAST_ACCEPTED_WALL_MS, 0L).takeIf { it > 0 })

    /** null, если последняя отправка прошла или ошибок с запуска процесса не было. */
    val lastError: StateFlow<String?> = lastErrorState.asStateFlow()

    /** Настенное время последнего ответа 202 в миллисекундах; null, если сервер ещё ни разу не принял пачку. */
    val lastAcceptedWallMs: StateFlow<Long?> = lastAcceptedState.asStateFlow()

    /** Запоминает текст ошибки, который покажут экраны настроек. */
    fun reportError(message: String) {
        lastErrorState.value = message
    }

    /** Сервер принял пачку или heartbeat в [wallMs]: прежняя ошибка больше не актуальна. */
    fun reportAccepted(wallMs: Long) {
        prefs.edit { putLong(KEY_LAST_ACCEPTED_WALL_MS, wallMs) }
        lastAcceptedState.value = wallMs
        lastErrorState.value = null
    }

    private companion object {
        const val PREFS = "upload"
        const val KEY_LAST_ACCEPTED_WALL_MS = "last_accepted_wall_ms"
    }
}
