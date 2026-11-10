package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/**
 * Тип события группы. Какие поля [ControlEvent] нужны каждому типу, написано у самих полей.
 *
 * [UNKNOWN] получает тип из будущей версии протокола: `MeshCodec` подставляет его вместо незнакомого
 * значения. Такое событие хранится и пересылается, чтобы не разорвать непрерывный префикс автора,
 * но `GroupCalculator` его пропускает.
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
 * Вид снятия тревоги в событии RESOLVE. [SELF_OK] присылает сам участник, о котором тревога, [SEEN_OK] любой
 * другой участник, [FALSE_ALARM] руководитель или автор тревоги. Если вид прислал не тот, кому он положен,
 * правила тревог в `domain/rules/AlarmRules.kt` снятие не засчитывают.
 */
enum class ResolveKind { SELF_OK, SEEN_OK, FALSE_ALARM }

/**
 * Событие группы: действие человека (вступил, объявил тревогу, ответил на перекличку), которое расходится
 * по всем телефонам и больше не меняется.
 *
 * Создаёт его хранилище автора в `MeshStore.appendOwnEvent` по просьбе `GroupActions`, дальше его пересылает
 * `SyncEngine`, а `GroupCalculator` выводит из множества событий состав группы, тревоги и перекличку.
 * Порядок прихода событий на результат не влияет. [seq] идёт подряд с единицы в потоке событий автора,
 * у точек трека свой поток и своя нумерация.
 *
 * Значения по умолчанию нужны декодеру: отсутствующее поле становится `null` или пустым списком,
 * а незнакомый тип или вид снятия превращается в [EventType.UNKNOWN] или `null`.
 */
@Serializable
data class ControlEvent(
    val id: EventId,
    val author: MemberId,
    val seq: Long,
    /** Время создания по часам автора, мс от эпохи. Только для подписи «по часам автора», в расчётах не участвует. */
    val createdAtWallMs: Long,
    val type: EventType = EventType.UNKNOWN,
    /** О ком событие: у ALARM и RESOLVE. */
    val target: MemberId? = null,
    /** Ссылки на другие события: у RESOLVE на снимаемые тревоги, у ROLLCALL_ANSWER на перекличку. */
    val refs: List<EventId> = emptyList(),
    /** Имя участника: у JOINED. */
    val name: String? = null,
    /** Длительность отлучки в миллисекундах: у AWAY; 0 отменяет отлучку. */
    val durationMs: Long? = null,
    /** Вид снятия: у RESOLVE. */
    val resolveKind: ResolveKind? = null,
)
