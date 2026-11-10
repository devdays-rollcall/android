package ru.pereklichka.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Последняя ошибка отправки на сервер для экрана диагностики. Пишет её [UploadWorker], например при ответе 401,
 * когда повторять бесполезно и человеку нужно проверить адрес сервера или токен.
 */
@Singleton
class UploadStatus @Inject constructor() {

    private val lastErrorState = MutableStateFlow<String?>(null)

    /** null, если последняя отправка прошла или отправок ещё не было. */
    val lastError: StateFlow<String?> = lastErrorState.asStateFlow()

    fun reportError(message: String) {
        lastErrorState.value = message
    }

    fun clearError() {
        lastErrorState.value = null
    }
}
