package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/**
 * Состояние участника: последняя позиция, заряд и кого его телефон слышит.
 *
 * Пишет его `SelfStateProducer` через `MeshStore.putOwnState` раз в 15 секунд и сразу после своего события или
 * нового соседа. `SyncEngine` разносит его по группе, а `GroupCalculator` берёт из него позицию, заряд и сведения
 * для оценки последнего контакта.
 * На каждом телефоне хранится одно состояние на участника: новое заменяет старое, если у него больше [seq].
 * Своё состояние от других телефон не принимает.
 *
 * Возраст позиции у получателя равен возрасту самого состояния плюс [fixAgeMs].
 */
@Serializable
data class MemberState(
    val member: MemberId,
    val seq: Long,
    val position: LatLon? = null,
    val accuracyM: Double? = null,
    /** Сколько миллисекунд прошло от фиксации до создания состояния. Есть, если есть [position]. */
    val fixAgeMs: Long? = null,
    /** Заряд в процентах, 0..100. */
    val batteryPercent: Int? = null,
    /** Кого телефон слышал напрямую за последние 10 минут и сколько миллисекунд назад на момент создания. */
    val heardAgoMs: Map<MemberId, Long> = emptyMap(),
)
