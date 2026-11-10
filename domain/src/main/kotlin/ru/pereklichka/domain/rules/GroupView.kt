package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.time.Age

/**
 * Вид группы с этого телефона: участники со статусами, активные тревоги, перекличка и подсказки.
 *
 * Считает его [GroupCalculator], публикует `GroupViewProvider` раз в 2 секунды и при изменении данных. Экраны
 * получают его через `GroupViewModel`, а `StatusMonitor` по разнице двух видов решает, какие уведомления
 * показать. Экраны только рисуют то, что пришло: возраст и статусы уже посчитаны, внутри composable их
 * не считают.
 */
data class GroupView(
    val group: GroupInfo,
    val me: MemberId,
    val iAmLeader: Boolean,
    val route: RouteState,
    /** Все участники из событий JOINED, я первым, остальные по имени. */
    val members: List<MemberView>,
    /** Только активные тревоги. */
    val alarms: List<AlarmView>,
    /** Последняя перекличка, если её окно не истекло. */
    val rollcall: RollcallView?,
    /** Подсказки «Рядом телефон участника <имя>. Вы видите этого человека?», см. [NearbyPrompt]. */
    val prompts: List<NearbyPrompt>,
)

/**
 * Состояние маршрута: LOBBY («Сбор», люди вступают), ACTIVE (маршрут идёт, пишется трек), FINISHED (завершён).
 * Вычисляется из событий ROUTE_STARTED и ROUTE_FINISHED руководителя, отдельного флага нет.
 */
enum class RouteState { LOBBY, ACTIVE, FINISHED }

/** Присутствие участника на экране: OK «Норма», AWAY «Отлучка», LOST «Не слышно». */
enum class Presence { OK, AWAY, LOST }

/** Участник группы для списка и карточки. */
data class MemberView(
    val id: MemberId,
    val name: String,
    val isLeader: Boolean,
    val isMe: Boolean,
    val presence: Presence,
    /** Сколько назад группа последний раз слышала участника; null, если не слышала ни разу или это я. */
    val lastContact: Age?,
    /** Мой телефон слышал участника напрямую не позже 30 с назад. */
    val heardDirectlyNow: Boolean,
    /**
     * Кто слышал участника напрямую, когда случился [lastContact]: я сам или участник, в чьём состоянии он записан
     * в `heardAgoMs`. null, если свежее всего оказалась собственная запись участника или контакта не было.
     */
    val lastHeardBy: MemberId?,
    /** Последняя известная позиция из его состояния. */
    val position: LatLon?,
    /** Возраст позиции: возраст состояния плюс возраст фиксации в нём. */
    val positionAge: Age?,
    /** Точность позиции, м. */
    val accuracyM: Double?,
    /** Расстояние в метрах от моей последней фиксации до его позиции; null, если чьей-то позиции нет. */
    val distanceM: Double?,
    /** Начальный азимут от меня на него в градусах от севера. */
    val bearingDeg: Double?,
    /** Заряд в процентах, 0..100. */
    val batteryPercent: Int?,
    /** Сколько миллисекунд осталось до конца отлучки; null, если отлучки нет. */
    val awayRemainingMs: Long?,
    /** Есть активная тревога о нём, в том числе SOS. */
    val hasActiveAlarm: Boolean,
    /** Среди активных тревог о нём есть его собственный SOS. */
    val hasSos: Boolean,
)

/** Активная тревога или SOS о [target]. У SOS [raisedBy] совпадает с [target]. [age] считается от создания тревоги. */
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
 * Перекличка руководителя, окно которой ещё не истекло ([remainingMs] миллисекунд до конца окна).
 * Три списка участников, кроме руководителя, не пересекаются: ответили, рядом но молчат (не ответили
 * и не «Не слышно»), не слышно.
 */
data class RollcallView(
    val id: EventId,
    val remainingMs: Long,
    val answered: List<MemberId>,
    val silentNearby: List<MemberId>,
    val notHeard: List<MemberId>,
)

/**
 * Подсказка о близости: о [member] есть активная тревога, а мой телефон слышит его напрямую.
 * Ответ «Вижу, в порядке» вызывает `GroupActions.resolve(member, SEEN_OK)`, ссылки на тревоги оно соберёт само.
 * Радиоконтакт сам тревогу не снимает, решает человек.
 */
data class NearbyPrompt(
    val member: MemberId,
    val name: String,
)
