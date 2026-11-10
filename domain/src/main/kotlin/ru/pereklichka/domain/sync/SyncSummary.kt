package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.MemberId

/**
 * Что у телефона есть: по каждому автору последний номер в непрерывном префиксе событий и точек
 * и номер последнего состояния каждого участника.
 *
 * Возвращает его `MeshStore.summary()`, движок кладёт его в [Hello]. Размер зависит только от числа
 * участников, а не от числа записей. Автора, от которого ничего нет, в картах нет совсем.
 */
data class SyncSummary(
    val control: Map<MemberId, Long>,
    val locations: Map<MemberId, Long>,
    val states: Map<MemberId, Long>,
)
