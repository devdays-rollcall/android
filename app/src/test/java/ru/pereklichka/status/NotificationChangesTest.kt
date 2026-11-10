package ru.pereklichka.status

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age
import ru.pereklichka.notify.NotifyEvent
import ru.pereklichka.ui.FakeGroupViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Переходы `StatusMonitor` из `docs/team/techlead.md`. */
class NotificationChangesTest {

    private val leaderView = FakeGroupViews.leader.copy(alarms = emptyList(), rollcall = null, prompts = emptyList())
    private val participantView = FakeGroupViews.participant.copy(alarms = emptyList(), rollcall = null, prompts = emptyList())
    private val valentin = leaderView.members.single { it.name == "Валентин" }
    private val olga = leaderView.members.single { it.name == "Ольга" }
    private val alarm = AlarmView(EventId("alarm"), valentin.id, "Валентин", olga.id, "Ольга", isSos = false, Age(0, false))

    @Test
    fun `новая тревога показывается один раз, снятая убирается`() {
        val withAlarm = leaderView.copy(alarms = listOf(alarm))

        assertEquals(
            listOf(NotifyEvent.AlarmRaised(alarm.id, valentin.id, "Валентин", isSos = false, isAboutMe = false)),
            notificationChanges(leaderView, withAlarm).show,
        )
        assertEquals(emptyList(), notificationChanges(withAlarm, withAlarm).show)
        assertEquals(listOf(NotifyEvent.AlarmResolved(alarm.id)), notificationChanges(withAlarm, leaderView).show)
    }

    @Test
    fun `тревога обо мне помечается`() {
        val aboutMe = alarm.copy(target = participantView.me)

        val shown = notificationChanges(participantView, participantView.copy(alarms = listOf(aboutMe))).show

        assertEquals(true, (shown.single() as NotifyEvent.AlarmRaised).isAboutMe)
    }

    @Test
    fun `после перезапуска об активных тревогах напоминают снова`() {
        val shown = notificationChanges(previous = null, current = leaderView.copy(alarms = listOf(alarm))).show

        assertTrue(shown.any { it is NotifyEvent.AlarmRaised })
    }

    @Test
    fun `«Не слышно» звучит только у руководителя на маршруте`() {
        val before = leaderView.withPresence(valentin.id.value, Presence.OK)
        val after = leaderView.withPresence(valentin.id.value, Presence.LOST)

        assertEquals(listOf(NotifyEvent.MemberLost(valentin.id, "Валентин")), notificationChanges(before, after).show)
        assertEquals(emptyList(), notificationChanges(before.copy(route = RouteState.LOBBY), after.copy(route = RouteState.LOBBY)).show)
        assertEquals(
            emptyList(),
            notificationChanges(
                participantView.withPresence(valentin.id.value, Presence.OK),
                participantView.withPresence(valentin.id.value, Presence.LOST),
            ).show,
        )
        assertEquals(listOf(NotifyEvent.lostKey(valentin.id)), notificationChanges(after, before).dismiss)
    }

    @Test
    fun `перекличка без моего ответа даёт уведомление, ответ его убирает`() {
        val rollcall = FakeGroupViews.leader.rollcall!!
        val asked = participantView.copy(rollcall = rollcall)
        val answered = participantView.copy(rollcall = rollcall.copy(answered = rollcall.answered + participantView.me))

        assertEquals(listOf(NotifyEvent.RollcallStarted(rollcall.id)), notificationChanges(participantView, asked).show)
        assertEquals(listOf(NotifyEvent.rollcallKey(rollcall.id)), notificationChanges(asked, answered).dismiss)
        assertEquals(emptyList(), notificationChanges(leaderView, leaderView.copy(rollcall = rollcall)).show)
    }

    @Test
    fun `подсказка о близости появляется и исчезает`() {
        val prompted = leaderView.copy(prompts = listOf(NearbyPrompt(valentin.id, "Валентин")))

        assertEquals(listOf(NotifyEvent.NearbyConfirm(valentin.id, "Валентин")), notificationChanges(leaderView, prompted).show)
        assertEquals(listOf(NotifyEvent.nearbyKey(valentin.id)), notificationChanges(prompted, leaderView).dismiss)
    }

    private fun GroupView.withPresence(memberId: String, presence: Presence) =
        copy(members = members.map { if (it.id.value == memberId) it.copy(presence = presence) else it })
}
