package ru.pereklichka.domain.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.domain.time.Stamped
import ru.pereklichka.domain.time.ageNow

/**
 * Сообщение между соседями: сводка [Hello] или порция записей [Delta]. Кодирует и разбирает его [MeshCodec].
 *
 * Формат меняется только вместе с увеличением [Hello.PROTOCOL_VERSION], потому что телефоны с разным форматом
 * не смогут обмениваться.
 */
@Serializable
sealed interface MeshMessage

/**
 * Сводка: что у меня есть. Уходит при подключении, каждые 10 с и после [Delta] с `more`.
 * Она же работает как heartbeat: по ней сосед понимает, что я рядом.
 *
 * Получатель заменяет свою оценку того, что есть у соседа (`peerKnown`), содержимым сводки целиком.
 * Карты устроены как в [SyncSummary].
 */
@Serializable
@SerialName("hello")
data class Hello(
    /** Версия протокола отправителя. */
    val v: Int,
    /** Тег группы; при несовпадении соединение рвётся. */
    val groupTag: String,
    /** member_id отправителя; должен совпасть с именем устройства в Nearby. */
    val from: MemberId,
    val control: Map<MemberId, Long> = emptyMap(),
    val locations: Map<MemberId, Long> = emptyMap(),
    val states: Map<MemberId, Long> = emptyMap(),
) : MeshMessage {

    fun summary(): SyncSummary = SyncSummary(control = control, locations = locations, states = states)

    companion object {
        const val PROTOCOL_VERSION: Int = 1

        fun of(summary: SyncSummary, groupTag: String, from: MemberId): Hello = Hello(
            v = PROTOCOL_VERSION,
            groupTag = groupTag,
            from = from,
            control = summary.control,
            locations = summary.locations,
            states = summary.states,
        )
    }
}

/**
 * Delta: порция записей, которых, по оценке отправителя, у соседа нет.
 *
 * Записи одного автора идут по возрастанию номера. [more] значит, что у отправителя есть ещё что-то
 * для соседа: получатель сразу шлёт свежую сводку и получает следующую порцию.
 */
@Serializable
@SerialName("delta")
data class Delta(
    val control: List<WireControlEvent> = emptyList(),
    val states: List<WireMemberState> = emptyList(),
    val locations: List<LocationPoint> = emptyList(),
    val more: Boolean = false,
) : MeshMessage {

    /** Превращает Delta в записи с отметкой свежести для `MeshStore.apply`. */
    fun toIncoming(clock: Clock): IncomingDelta = IncomingDelta(
        control = control.map { Stamped.received(it.event, it.ageMs, clock) },
        states = states.map { Stamped.received(it.state, it.ageMs, clock) },
        locations = locations,
    )
}

/** Событие в Delta вместе с его возрастом на момент отправки, мс. */
@Serializable
data class WireControlEvent(val event: ControlEvent, val ageMs: Long)

/** Состояние в Delta вместе с его возрастом на момент отправки, мс. */
@Serializable
data class WireMemberState(val state: MemberState, val ageMs: Long)

/** Событие из хранилища в виде для отправки: возраст пересчитан на текущий момент. */
@JvmName("controlEventToWire")
fun Stamped<ControlEvent>.toWire(clock: Clock): WireControlEvent = WireControlEvent(value, ageNow(clock).ms)

/** Состояние из хранилища в виде для отправки: возраст пересчитан на текущий момент. */
@JvmName("memberStateToWire")
fun Stamped<MemberState>.toWire(clock: Clock): WireMemberState = WireMemberState(value, ageNow(clock).ms)
