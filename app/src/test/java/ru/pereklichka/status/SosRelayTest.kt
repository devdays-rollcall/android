package ru.pereklichka.status

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.FakeGroupViews
import kotlin.test.Test
import kotlin.test.assertEquals

class SosRelayTest {

    private val ann = MemberId("ann")
    private val bob = MemberId("bob")
    private val sosOfAnn = AlarmView(EventId("sos-ann"), seq = 3, ann, "Аня", ann, "Аня", isSos = true, Age(0, false))
    private val alarmAboutBob = AlarmView(EventId("alarm-bob"), seq = 4, bob, "Боб", ann, "Аня", isSos = false, Age(0, false))
    private val quiet = FakeGroupViews.participant.copy(alarms = emptyList())

    @Test
    fun `чужой SOS уходит на сервер, а обычная тревога нет`() {
        val changes = sosRelayChanges(quiet, quiet.copy(alarms = listOf(sosOfAnn, alarmAboutBob)))
        assertEquals(SosRelayChanges(send = listOf(sosOfAnn), cancel = emptyList()), changes)
    }

    @Test
    fun `тот же SOS второй раз не ставится, снятый снимает отправку`() {
        val withSos = quiet.copy(alarms = listOf(sosOfAnn))
        assertEquals(SosRelayChanges(emptyList(), emptyList()), sosRelayChanges(withSos, withSos))
        assertEquals(SosRelayChanges(send = emptyList(), cancel = listOf(sosOfAnn)), sosRelayChanges(withSos, quiet))
    }

    @Test
    fun `после перезапуска службы ставятся все активные SOS`() {
        val withSos = quiet.copy(alarms = listOf(sosOfAnn, alarmAboutBob))
        assertEquals(SosRelayChanges(send = listOf(sosOfAnn), cancel = emptyList()), sosRelayChanges(null, withSos))
    }
}
