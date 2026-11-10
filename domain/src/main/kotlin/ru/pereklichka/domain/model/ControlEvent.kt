package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/**
 * Типы событий. Какие поля [ControlEvent] нужны каждому типу, написано у самих полей.
 *
 * [UNKNOWN] получает тип из будущей версии протокола: декодер подставляет его вместо незнакомого
 * значения. Такое событие хранится и пересылается, чтобы не разорвать префикс автора, но правила
 * его не учитывают.
 */
enum class EventType {
    JOINED,
    ROUTE_STARTED,
    AWAY,
    ROLLCALL,
    ROLLCALL_ANSWER,
    ALARM,
    SOS,
    RESOLVE,
    ROUTE_FINISHED,
    UNKNOWN,
}

/**
 * Вид снятия тревоги. Кто вправе снимать каким видом, проверяет `GroupCalculator`:
 * [SELF_OK] присылает сам участник, [SEEN_OK] кто угодно, кроме него, [FALSE_ALARM] руководитель
 * или автор тревоги.
 */
enum class ResolveKind { SELF_OK, SEEN_OK, FALSE_ALARM }

/**
 * Событие: действие человека, которое расходится по всей группе.
 *
 * Создаёт его хранилище автора в `MeshStore.appendOwnEvent`, после этого событие не меняется.
 * [seq] идёт подряд с единицы в потоке событий автора, отдельно от номеров точек.
 * Состав группы и все статусы вычисляются из множества событий, порядок их прихода не важен.
 *
 * Значения по умолчанию нужны декодеру: отсутствующее поле становится `null` или пустым списком,
 * а незнакомый тип или вид снятия превращается в [EventType.UNKNOWN] или `null`.
 */
@Serializable
data class ControlEvent(
    val id: EventId,
    val author: MemberId,
    val seq: Long,
    /** Время создания по часам автора. Только для подписи «по часам автора», в расчётах не участвует. */
    val createdAtWallMs: Long,
    val type: EventType = EventType.UNKNOWN,
    /** О ком событие: у ALARM и RESOLVE. */
    val target: MemberId? = null,
    /** Ссылки на другие события: у RESOLVE на снимаемые тревоги, у ROLLCALL_ANSWER на перекличку. */
    val refs: List<EventId> = emptyList(),
    /** Имя участника: у JOINED. */
    val name: String? = null,
    /** Длительность отлучки: у AWAY; 0 отменяет отлучку. */
    val durationMs: Long? = null,
    /** Вид снятия: у RESOLVE. */
    val resolveKind: ResolveKind? = null,
)
