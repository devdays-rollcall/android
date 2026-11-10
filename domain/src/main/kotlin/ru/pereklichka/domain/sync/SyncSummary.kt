package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.MemberId

/**
 * Сводка того, что есть у телефона: по каждому автору последний номер в непрерывном префиксе событий и точек,
 * по каждому участнику номер его последнего состояния.
 *
 * Возвращает её [MeshStore.summary], [SyncEngine] кладёт её в [Hello]. Размер сводки зависит от числа участников
 * и не растёт с числом записей. Автора, от которого ничего нет, в картах нет совсем.
 */
data class SyncSummary(
    val control: Map<MemberId, Long>,
    val locations: Map<MemberId, Long>,
    val states: Map<MemberId, Long>,
)
