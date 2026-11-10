package ru.pereklichka.service

import kotlinx.coroutines.flow.Flow
import ru.pereklichka.domain.model.Fix

/** Источник фиксаций GPS для [TrackRecorder]. На телефоне это [FusedLocationSource], в тестах фейк. */
interface LocationSource {
    /** Холодный поток фиксаций раз в 5 с: подписка включает GPS, отписка выключает. */
    val fixes: Flow<Fix>
}
