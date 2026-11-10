package ru.pereklichka.service

import kotlinx.coroutines.flow.Flow
import ru.pereklichka.domain.model.Fix

/** Фиксации GPS. На телефоне это `FusedLocationSource`, в тестах фейк. */
interface LocationSource {
    /** Холодный поток: подписка включает GPS, отписка выключает. */
    val fixes: Flow<Fix>
}
