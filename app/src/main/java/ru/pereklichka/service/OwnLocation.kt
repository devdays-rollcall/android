package ru.pereklichka.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.pereklichka.domain.model.Fix
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранит мою последнюю фиксацию GPS. Пишет её [TrackRecorder], читают [SelfStateProducer] (позиция в своём
 * состоянии) и `GroupViewProvider` (расстояния до участников). Фиксация уже очищена `LocationSanitizer`.
 */
@Singleton
class OwnLocation @Inject constructor() {

    private val lastFixState = MutableStateFlow<Fix?>(null)

    /** null, пока GPS не дал ни одной фиксации с момента запуска процесса. */
    val lastFix: StateFlow<Fix?> = lastFixState.asStateFlow()

    fun update(fix: Fix) {
        lastFixState.value = fix
    }
}
