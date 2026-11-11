package ru.pereklichka.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.NearbyPrompt
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age
import ru.pereklichka.notify.Notifier
import ru.pereklichka.notify.NotifyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** После остановки монитора в шторке не остаётся его уведомлений и не звучит тревога. */
class StatusMonitorTest {

    private val leader = MemberId("leader")
    private val bob = MemberId("bob")
    private val alarm = AlarmView(EventId("alarm"), bob, "Боб", leader, "Руководитель", isSos = false, Age(0, false))
    private val calm = GroupView(
        group = GroupInfo("tag", "token", "Поход", leaderId = leader, me = leader, createdAtWallMs = 0),
        me = leader,
        iAmLeader = true,
        route = RouteState.ACTIVE,
        members = emptyList(),
        alarms = emptyList(),
        rollcall = null,
        prompts = emptyList(),
    )

    @Test
    fun `при отмене монитор убирает всё, что показал`() = runTest {
        val notifier = FakeNotifier()
        val views = MutableStateFlow(calm.copy(alarms = listOf(alarm), prompts = listOf(NearbyPrompt(bob, "Боб"))))
        val monitor = launch { showNotifications(views, notifier) }
        runCurrent()

        monitor.cancel()
        runCurrent()

        assertEquals(setOf(NotifyEvent.alarmKey(alarm.id), NotifyEvent.nearbyKey(bob)), notifier.dismissed.toSet())
    }

    @Test
    fun `уже снятое при отмене второй раз не убирается`() = runTest {
        val notifier = FakeNotifier()
        val views = MutableStateFlow(calm.copy(alarms = listOf(alarm), prompts = listOf(NearbyPrompt(bob, "Боб"))))
        val monitor = launch { showNotifications(views, notifier) }
        runCurrent()
        views.value = calm
        runCurrent()
        notifier.dismissed.clear()

        monitor.cancel()
        runCurrent()

        assertEquals(emptyList(), notifier.dismissed)
    }

    private class FakeNotifier : Notifier {
        val dismissed = mutableListOf<String>()

        override fun show(event: NotifyEvent) = Unit

        override fun dismiss(key: String) {
            dismissed += key
        }
    }
}
