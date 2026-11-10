package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Идентификатор участника: UUIDv4 строкой.
 *
 * Телефон создаёт новый id при каждом создании группы или вступлении в неё и хранит его в [GroupInfo.me].
 * Тот же id — имя устройства в Nearby и `member_id` на сервере. В пределах группы он не меняется:
 * на нём держится нумерация записей автора.
 */
@Serializable
@JvmInline
value class MemberId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): MemberId = MemberId(UUID.randomUUID().toString())
    }
}

/**
 * Идентификатор записи: UUIDv4 строкой. Его получают события ([ControlEvent]) и точки трека ([LocationPoint]).
 *
 * Создаётся один раз при записи и не меняется ни при пересылке между телефонами, ни при повторной
 * отправке на сервер: по нему сервер отсеивает повторы.
 */
@Serializable
@JvmInline
value class EventId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): EventId = EventId(UUID.randomUUID().toString())
    }
}
