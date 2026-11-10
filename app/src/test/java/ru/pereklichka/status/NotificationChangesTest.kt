package ru.pereklichka.status

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age
import ru.pereklichka.notify.NotifyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Переходы `StatusMonitor` из KDoc [notificationChanges]. */
class NotificationChangesTest {

    private val leader = MemberId("leader")
    private val ann = MemberId("ann")
    private val bob = MemberId("bob")
    private val alarm = AlarmView(EventId("alarm"), bob, "Боб", ann, "Аня", isSos = false, Age(0, false))
    private val rollcall = RollcallView(EventId("rollcall"), remainingMs = 60_000, answered = emptyList(),
        silentNearby = listOf(ann, bob), notHeard = emptyList())

    private val leaderView = view(me = leader)
    private val annView = view(me = ann)

    @Test
    fun `новая тревога показывается один раз, снятая убирается`() {
        val withAlarm = leaderView.copy(alarms = listOf(alarm))

        assertEquals(
            listOf(NotifyEvent.AlarmRaised(alarm.id, bob, "Боб", isSos = false, isAboutMe = false)),
            notificationChanges(leaderView, withAlarm).show,
        )
        assertEquals(emptyList(), notificationChanges(withAlarm, withAlarm).show)
        assertEquals(listOf(NotifyEvent.AlarmResolved(alarm.id)), notificationChanges(withAlarm, leaderView).show)
    }

    @Test
    fun `тревога обо мне помечается`() {
        val shown = notificationChanges(annView, annView.copy(alarms = listOf(alarm.copy(target = ann)))).show

        assertEquals(true, (shown.single() as NotifyEvent.AlarmRaised).isAboutMe)
    }

    @Test
    fun `после перезапуска об активных тревогах напоминают снова`() {
        val shown = notificationChanges(previous = null, current = leaderView.copy(alarms = listOf(alarm))).show

        assertTrue(shown.any { it is NotifyEvent.AlarmRaised })
    }

    @Test
    fun `«Не слышно» звучит только у руководителя на маршруте`() {
        val bobLost = leaderView.withPresence(bob, Presence.LOST)

        assertEquals(listOf(NotifyEvent.MemberLost(bob, "Боб")), notificationChanges(leaderView, bobLost).show)
        assertEquals(listOf(NotifyEvent.lostKey(bob)), notificationChanges(bobLost, leaderView).dismiss)
        assertEquals(emptyList(), notificationChanges(annView, annView.withPresence(bob, Presence.LOST)).show)
        assertEquals(
            emptyList(),
            notificationChanges(leaderView.copy(route = RouteState.LOBBY), bobLost.copy(route = RouteState.LOBBY)).show,
        )
    }

    @Test
    fun `кого не слышно в момент выхода, о том сообщают, а после завершения уведомление убирают`() {
        val lobby = leaderView.copy(route = RouteState.LOBBY).withPresence(bob, Presence.LOST)
        val active = lobby.copy(route = RouteState.ACTIVE)
        val finished = lobby.copy(route = RouteState.FINISHED)

        assertEquals(listOf(NotifyEvent.MemberLost(bob, "Боб")), notificationChanges(lobby, active).show)
        assertEquals(listOf(NotifyEvent.lostKey(bob)), notificationChanges(active, finished).dismiss)
    }

    @Test
    fun `перекличка без моего ответа даёт уведомление, ответ его убирает`() {
        val asked = annView.copy(rollcall = rollcall)
        val answered = annView.copy(rollcall = rollcall.copy(answered = listOf(ann), silentNearby = listOf(bob)))

        assertEquals(listOf(NotifyEvent.RollcallStarted(rollcall.id)), notificationChanges(annView, asked).show)
        assertEquals(listOf(NotifyEvent.rollcallKey(rollcall.id)), notificationChanges(asked, answered).dismiss)
        assertEquals(emptyList(), notificationChanges(leaderView, leaderView.copy(rollcall = rollcall)).show)
    }

    @Test
    fun `подсказка о близости появляется и исчезает`() {
        val prompted = leaderView.copy(prompts = listOf(NearbyPrompt(bob, "Боб")))

        assertEquals(listOf(NotifyEvent.NearbyConfirm(bob, "Боб")), notificationChanges(leaderView, prompted).show)
        assertEquals(listOf(NotifyEvent.nearbyKey(bob)), notificationChanges(prompted, leaderView).dismiss)
    }

    private fun view(me: MemberId) = GroupView(
        group = GroupInfo("tag", "token", "Поход", leaderId = leader, me = me, createdAtWallMs = 0),
        me = me,
        iAmLeader = me == leader,
        route = RouteState.ACTIVE,
        members = listOf(member(leader, "Руководитель", me), member(ann, "Аня", me), member(bob, "Боб", me)),
        alarms = emptyList(),
        rollcall = null,
        prompts = emptyList(),
    )

    private fun member(id: MemberId, name: String, me: MemberId) = MemberView(
        id = id, name = name, isLeader = id == leader, isMe = id == me, presence = Presence.OK,
        lastContact = Age(5_000, false), heardDirectlyNow = true, lastHeardBy = me, position = null, positionAge = null,
        accuracyM = null, distanceM = null, bearingDeg = null, batteryPercent = null, awayRemainingMs = null,
        hasActiveAlarm = false, hasSos = false,
    )

    private fun GroupView.withPresence(member: MemberId, presence: Presence) =
        copy(members = members.map { if (it.id == member) it.copy(presence = presence) else it })
}
