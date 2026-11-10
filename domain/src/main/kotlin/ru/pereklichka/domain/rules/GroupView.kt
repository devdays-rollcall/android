package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.time.Age

/**
 * Всё, что показывают экраны и на что реагирует `StatusMonitor`. Считает его [GroupCalculator],
 * публикует `GroupViewProvider` раз в 2 секунды и при изменении данных.
 *
 * Экраны только рисуют то, что пришло: возраст и статусы уже посчитаны, внутри composable их не считают.
 */
data class GroupView(
    val group: GroupInfo,
    val me: MemberId,
    val iAmLeader: Boolean,
    val route: RouteState,
    /** Я первым. */
    val members: List<MemberView>,
    /** Только активные тревоги. */
    val alarms: List<AlarmView>,
    /** Последняя перекличка, если её окно не истекло. */
    val rollcall: RollcallView?,
    /** «Рядом телефон участника <имя>. Вы видите этого человека?» */
    val prompts: List<NearbyPrompt>,
)

/** Состояние маршрута. Вычисляется из событий руководителя, отдельного флага нет. */
enum class RouteState { LOBBY, ACTIVE, FINISHED }

/** Присутствие участника: «Норма», «Отлучка», «Не слышно». */
enum class Presence { OK, AWAY, LOST }

/** Участник группы для списка и карточки. */
data class MemberView(
    val id: MemberId,
    val name: String,
    val isLeader: Boolean,
    val isMe: Boolean,
    val presence: Presence,
    /** Когда группа последний раз слышала участника; null, если не слышала ни разу. */
    val lastContact: Age?,
    val heardDirectlyNow: Boolean,
    /**
     * Кто слышал участника напрямую, когда случился [lastContact]: я сам или участник, в чьём состоянии он записан
     * в `heardAgoMs`. null, если свежее всего оказалась собственная запись участника или контакта не было.
     */
    val lastHeardBy: MemberId?,
    val position: LatLon?,
    val positionAge: Age?,
    val accuracyM: Double?,
    /** От моей последней фиксации до его последней позиции; null, если чьей-то позиции нет. */
    val distanceM: Double?,
    /** Начальный азимут от севера, в градусах. */
    val bearingDeg: Double?,
    val batteryPercent: Int?,
    /** Сколько осталось до конца отлучки; null, если отлучки нет. */
    val awayRemainingMs: Long?,
    /** Есть активная тревога о нём, в том числе SOS. */
    val hasActiveAlarm: Boolean,
    /** Среди активных тревог о нём есть его собственный SOS. */
    val hasSos: Boolean,
)

/** Активная тревога или SOS о [target]. У SOS [raisedBy] совпадает с [target]. */
data class AlarmView(
    val id: EventId,
    val target: MemberId,
    val targetName: String,
    val raisedBy: MemberId,
    val raisedByName: String,
    val isSos: Boolean,
    val age: Age,
)

/**
 * Перекличка руководителя, окно которой ещё не истекло. Три списка участников, кроме руководителя,
 * не пересекаются: ответили, рядом но молчат (не ответили и не «Не слышно»), не слышно.
 */
data class RollcallView(
    val id: EventId,
    val remainingMs: Long,
    val answered: List<MemberId>,
    val silentNearby: List<MemberId>,
    val notHeard: List<MemberId>,
)

/**
 * Подсказка: о [member] есть активная тревога, а мой телефон слышит его напрямую.
 * Ответ «Вижу, в порядке» вызывает `GroupActions.resolve(member, SEEN_OK)`, ссылки на тревоги оно соберёт само.
 */
data class NearbyPrompt(
    val member: MemberId,
    val name: String,
)
