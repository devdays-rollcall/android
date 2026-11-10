package ru.pereklichka.ui

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age

/**
 * Правдоподобная группа из пяти человек для превью экранов и фейкового режима (`DataModeModule`), в котором её
 * показывает [GroupViewModel].
 * Есть все состояния, которые нужно нарисовать: норма, отлучка, «Не слышно», активная тревога, перекличка в процессе.
 *
 * [leader] это вид с телефона руководителя, [participant] с телефона участника.
 */
object FakeGroupViews {

    private val nikita = MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c")
    private val olga = MemberId("2a4b6c8d-0e1f-4a3b-8c5d-6e7f8a9b0c1d")
    private val vasilisa = MemberId("5d6e7f80-9a1b-4c2d-8e3f-4a5b6c7d8e9f")
    private val valentin = MemberId("7f8091a2-b3c4-4d5e-9f60-718293a4b5c6")
    private val daniil = MemberId("a1b2c3d4-e5f6-4789-8abc-def012345678")

    private val alarmAboutValentin = EventId("0f1e2d3c-4b5a-4968-8776-655443322110")
    private val rollcall = EventId("11223344-5566-4778-8899-aabbccddeeff")

    private val group = GroupInfo(
        tag = "3fa2c1d0b9e87766",
        token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        name = "Эльбрус, северный склон",
        leaderId = nikita,
        me = nikita,
        createdAtWallMs = 1_794_300_000_000,
    )

    private val members = listOf(
        member(nikita, "Никита", isLeader = true, presence = Presence.OK, contactMs = null, position = LatLon(43.3499, 42.4453)),
        member(olga, "Ольга", presence = Presence.OK, contactMs = 4_000, heardDirectly = true, position = LatLon(43.3503, 42.4450),
            distanceM = 46.0, bearingDeg = 332.0, battery = 81),
        member(daniil, "Даниил", presence = Presence.OK, contactMs = 22_000, heardBy = olga, position = LatLon(43.3511, 42.4448),
            distanceM = 138.0, bearingDeg = 344.0, battery = 64),
        member(vasilisa, "Василиса", presence = Presence.AWAY, contactMs = 95_000, awayRemainingMs = 8 * 60_000, battery = 47),
        member(valentin, "Валентин", presence = Presence.LOST, contactMs = 4 * 60_000, heardBy = daniil,
            position = LatLon(43.3530, 42.4431), distanceM = 389.0, bearingDeg = 333.0, battery = 12, hasAlarm = true),
    )

    /** Вид руководителя: идёт маршрут, о Валентине объявлена тревога, перекличка в процессе. */
    val leader = GroupView(
        group = group,
        me = nikita,
        iAmLeader = true,
        route = RouteState.ACTIVE,
        members = members,
        alarms = listOf(
            AlarmView(alarmAboutValentin, valentin, "Валентин", olga, "Ольга", isSos = false, Age(70_000, approximate = false)),
        ),
        rollcall = RollcallView(
            id = rollcall,
            remainingMs = 2 * 60_000,
            answered = listOf(olga),
            silentNearby = listOf(daniil, vasilisa),
            notHeard = listOf(valentin),
        ),
        prompts = emptyList(),
    )

    /** Тот же поход с телефона Даниила: он ещё не ответил на перекличку. */
    val participant = leader.copy(
        group = group.copy(me = daniil),
        me = daniil,
        iAmLeader = false,
        members = members.map { it.copy(isMe = it.id == daniil) }.sortedBy { !it.isMe },
    )

    private fun member(
        id: MemberId,
        name: String,
        isLeader: Boolean = false,
        presence: Presence,
        contactMs: Long?,
        heardDirectly: Boolean = false,
        heardBy: MemberId? = null,
        position: LatLon? = null,
        distanceM: Double? = null,
        bearingDeg: Double? = null,
        battery: Int? = null,
        awayRemainingMs: Long? = null,
        hasAlarm: Boolean = false,
    ) = MemberView(
        id = id,
        name = name,
        isLeader = isLeader,
        isMe = id == nikita,
        presence = presence,
        lastContact = contactMs?.let { Age(it, approximate = false) },
        heardDirectlyNow = heardDirectly,
        lastHeardBy = if (heardDirectly) nikita else heardBy,
        position = position,
        positionAge = position?.let { Age((contactMs ?: 0) + 3_000, approximate = false) },
        accuracyM = position?.let { 6.0 },
        distanceM = distanceM,
        bearingDeg = bearingDeg,
        batteryPercent = battery,
        awayRemainingMs = awayRemainingMs,
        hasActiveAlarm = hasAlarm,
        hasSos = false,
    )
}
