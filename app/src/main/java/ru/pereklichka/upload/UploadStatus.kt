package ru.pereklichka.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранит последнюю ошибку отправки на сервер для экрана диагностики. Пишет её [UploadWorker], например при
 * ответе 401, когда повторять бесполезно и человеку нужно проверить адрес сервера или токен.
 */
@Singleton
class UploadStatus @Inject constructor() {

    private val lastErrorState = MutableStateFlow<String?>(null)

    /** null, если последняя отправка прошла или отправок ещё не было. */
    val lastError: StateFlow<String?> = lastErrorState.asStateFlow()

    /** Запоминает текст ошибки, который покажет диагностика. */
    fun reportError(message: String) {
        lastErrorState.value = message
    }

    /** Сбрасывает ошибку после успешной отправки. */
    fun clearError() {
        lastErrorState.value = null
    }
}
