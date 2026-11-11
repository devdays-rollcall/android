package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.model.ResolveKind

/**
 * Своё событие до записи: всё, кроме id, автора, номера и времени, которые выдаёт хранилище.
 * Создаёт его `GroupActions` по действию человека и передаёт в [MeshStore.appendOwnEvent].
 * Какие поля нужны какому типу, написано в [ControlEvent].
 */
data class ControlEventDraft(
    val type: EventType,
    val target: MemberId? = null,
    val refs: List<EventId> = emptyList(),
    val name: String? = null,
    val durationMs: Long? = null,
    val resolveKind: ResolveKind? = null,
) {
    /**
     * Готовое событие. Вызывает хранилище внутри транзакции, где выдан [seq]. У имени отрезаются пробелы
     * по краям, и оно укорачивается до [MAX_NAME_LENGTH] символов.
     */
    fun toEvent(id: EventId, author: MemberId, seq: Long, createdAtWallMs: Long): ControlEvent = ControlEvent(
        id = id,
        author = author,
        seq = seq,
        createdAtWallMs = createdAtWallMs,
        type = type,
        target = target,
        refs = refs,
        name = name?.let(::limitName),
        durationMs = durationMs,
        resolveKind = resolveKind,
    )

    companion object {
        /**
         * Предел длины имени участника в JOINED, символов. С ним событие всегда влезает в одно сообщение
         * между телефонами. Запись, которая не влезает, движок не отправляет, и поток событий автора
         * у соседей на ней останавливается.
         */
        const val MAX_NAME_LENGTH: Int = 40

        private fun limitName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length <= MAX_NAME_LENGTH) return trimmed
            // Не разрезать суррогатную пару: половина эмодзи в JSON превратилась бы в мусор.
            val end = if (trimmed[MAX_NAME_LENGTH - 1].isHighSurrogate()) MAX_NAME_LENGTH - 1 else MAX_NAME_LENGTH
            return trimmed.substring(0, end).trimEnd()
        }
    }
}

/**
 * Своё состояние до записи. Создаёт его `SelfStateProducer` раз в 15 секунд и передаёт в [MeshStore.putOwnState].
 * [heardAgoMs] берётся из `PresenceTracker.heardAgo()`.
 */
data class MemberStateDraft(
    val fix: Fix?,
    val batteryPercent: Int?,
    val heardAgoMs: Map<MemberId, Long>,
) {
    /**
     * Готовое состояние.
     * @param nowElapsedMs текущее `Clock.elapsedMs()`. По нему момент фиксации переводится в возраст, потому что
     * монотонные часы получателя другие и сам момент ему ничего не скажет.
     */
    fun toState(member: MemberId, seq: Long, nowElapsedMs: Long): MemberState = MemberState(
        member = member,
        seq = seq,
        position = fix?.position,
        accuracyM = fix?.accuracyM,
        fixAgeMs = fix?.let { (nowElapsedMs - it.elapsedRealtimeMs).coerceAtLeast(0) },
        batteryPercent = batteryPercent,
        heardAgoMs = heardAgoMs,
    )
}

/** Точка трека из фиксации. Вызывает хранилище внутри транзакции, где выдан [seq]. */
fun Fix.toLocationPoint(id: EventId, author: MemberId, seq: Long, batteryPercent: Int?): LocationPoint = LocationPoint(
    id = id,
    author = author,
    seq = seq,
    latitude = position.latitude,
    longitude = position.longitude,
    recordedAtMs = timeUtcMs,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    batteryPercent = batteryPercent,
)
