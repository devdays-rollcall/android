package ru.pereklichka.ui.components

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.FakeGroupViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Подписи экранов группы, участника и переклички. */
class GroupLinesTest {

    private fun age(ms: Long, approximate: Boolean = false) = Age(ms, approximate)

    @Test
    fun `время в пути в минутах и часах`() {
        assertEquals("меньше минуты", formatDuration(59_000))
        assertEquals("14 мин", formatDuration(14 * 60_000 + 59_000))
        assertEquals("2 ч", formatDuration(120 * 60_000L))
        assertEquals("2 ч 14 мин", formatDuration(134 * 60_000L))
    }

    @Test
    fun `оставшееся время по секундам округляется вверх`() {
        assertEquals("2:00", formatClock(119_001))
        assertEquals("0:05", formatClock(4_500))
        assertEquals("0:00", formatClock(-1))
    }

    @Test
    fun `плашка маршрута по состоянию`() {
        assertEquals("Сбор", routeChipText(RouteState.LOBBY, null))
        assertEquals("На маршруте 2 ч 14 мин", routeChipText(RouteState.ACTIVE, age(134 * 60_000L)))
        assertEquals("На маршруте примерно 14 мин", routeChipText(RouteState.ACTIVE, age(14 * 60_000L, approximate = true)))
        assertEquals("На маршруте", routeChipText(RouteState.ACTIVE, null))
        assertEquals("Маршрут завершён", routeChipText(RouteState.FINISHED, age(1)))
    }

    @Test
    fun `строка статуса по тяжести`() {
        val heard = age(4 * 60_000)
        assertEquals("SOS · 4 мин назад", memberStatusLine(member(hasSos = true, hasActiveAlarm = true).copy(lastContact = heard)))
        assertEquals("Тревога · 4 мин назад", memberStatusLine(member(hasActiveAlarm = true).copy(lastContact = heard)))
        assertEquals("Не слышно · 4 мин назад", memberStatusLine(member(presence = Presence.LOST).copy(lastContact = heard)))
        assertEquals(
            "Отлучка · вернётся через 8 мин",
            memberStatusLine(member(presence = Presence.AWAY, awayRemainingMs = 7 * 60_000 + 1)),
        )
        assertEquals("Отлучка · срок вышел", memberStatusLine(member(presence = Presence.AWAY, awayRemainingMs = 0)))
    }

    @Test
    fun `в норме рядом, если слышу напрямую, иначе когда слышали`() {
        assertEquals("Рядом · только что", memberStatusLine(member().copy(lastContact = age(4_000), heardDirectlyNow = true)))
        assertEquals("Слышали 3 мин назад", memberStatusLine(member().copy(lastContact = age(3 * 60_000))))
        assertEquals("Ещё не слышали", memberStatusLine(member()))
    }

    @Test
    fun `заряд в списке только низкий`() {
        assertEquals(20, lowBattery(member().copy(batteryPercent = 20)))
        assertNull(lowBattery(member().copy(batteryPercent = 21)))
        assertNull(lowBattery(member()))
    }

    @Test
    fun `своя карточка считает остальных, кого слышу напрямую`() {
        assertEquals("Руководитель · слышу 1 из 4", selfSubtitle(FakeGroupViews.leader))
        assertEquals("Участник · слышу 1 из 4", selfSubtitle(FakeGroupViews.participant))
        val alone = FakeGroupViews.leader.let { view -> view.copy(members = view.members.filter { it.isMe }) }
        assertEquals("Руководитель", selfSubtitle(alone))
    }

    @Test
    fun `сводка переклички называет молчащих рядом`() {
        val a = MemberId("a")
        val b = MemberId("b")
        val c = MemberId("c")
        val rollcall = RollcallView(EventId("r"), 3, age(0), 60_000, answered = listOf(a), silentNearby = listOf(b), notHeard = listOf(c))
        val names = mapOf(a to "Ольга", b to "Даниил", c to "Валентин")
        assertEquals("Ответил 1 из 3 · молчат Даниил", rollcallSummary(rollcall, names))
        assertEquals("Ответили 2 из 3", rollcallSummary(rollcall.copy(answered = listOf(a, b), silentNearby = emptyList()), names))
    }

    @Test
    fun `сторона света словами`() {
        assertEquals("на северо-запад", directionWords(member(distanceM = 390.0, bearingDeg = 333.0)))
        assertEquals("на юг", directionWords(member(distanceM = 100.0, bearingDeg = 181.0)))
        assertNull(directionWords(member(distanceM = 3.0, bearingDeg = 90.0)))
        assertNull(directionWords(member(distanceM = 100.0, bearingDeg = 90.0, isMe = true)))
    }

    @Test
    fun `кто объявил тревогу`() {
        val me = MemberId("me")
        fun alarm(by: MemberId, name: String) =
            AlarmView(EventId("e-${by.value}"), 1, MemberId("t"), "Валентин", by, name, isSos = false, age(0))
        assertNull(raisedByText(emptyList(), me))
        assertEquals("объявил(а) Ольга", raisedByText(listOf(alarm(MemberId("o"), "Ольга")), me))
        assertEquals("объявили вы", raisedByText(listOf(alarm(me, "Никита")), me))
        assertEquals(
            "объявили Ольга, вы",
            raisedByText(listOf(alarm(MemberId("o"), "Ольга"), alarm(me, "Никита")), me),
        )
    }

    @Test
    fun `дошла ли перекличка до неответившего`() {
        val lost = member(presence = Presence.LOST).copy(lastContact = age(4 * 60_000))
        assertEquals("Не слышно 4 мин", rollcallPendingLine(lost, notHeard = true, received = false))
        assertEquals("Не слышно 4 мин, уведомление дошло", rollcallPendingLine(lost, notHeard = true, received = true))
        assertEquals("Не слышно", rollcallPendingLine(null, notHeard = true, received = false))
        assertEquals("Рядом, уведомление дошло", rollcallPendingLine(member(), notHeard = false, received = true))
        assertEquals("Рядом, ответа пока нет", rollcallPendingLine(member(), notHeard = false, received = false))
    }
}
