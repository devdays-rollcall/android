package ru.pereklichka.ui.components

import ru.pereklichka.domain.rules.Presence
import kotlin.test.Test
import kotlin.test.assertEquals

/** Статус участника в списке: SOS, тревога, «Не слышно», отлучка, норма. */
class StatusOfTest {

    @Test
    fun `SOS важнее тревоги и Не слышно`() {
        val status = statusOf(member(presence = Presence.LOST, hasActiveAlarm = true, hasSos = true))
        assertEquals(StatusInfo(StatusKind.ALARM, "SOS"), status)
    }

    @Test
    fun `тревога важнее Не слышно`() {
        assertEquals(StatusInfo(StatusKind.ALARM, "Тревога"), statusOf(member(presence = Presence.LOST, hasActiveAlarm = true)))
    }

    @Test
    fun `Не слышно и норма`() {
        assertEquals(StatusInfo(StatusKind.LOST, "Не слышно"), statusOf(member(presence = Presence.LOST)))
        assertEquals(StatusInfo(StatusKind.OK, "Норма"), statusOf(member()))
    }

    @Test
    fun `отлучка с оставшимся временем и без него`() {
        assertEquals(
            StatusInfo(StatusKind.AWAY, "Отлучка ещё 8 мин"),
            statusOf(member(presence = Presence.AWAY, awayRemainingMs = 7 * 60_000 + 30_000)),
        )
        assertEquals(StatusInfo(StatusKind.AWAY, "Отлучка"), statusOf(member(presence = Presence.AWAY, awayRemainingMs = 0)))
        assertEquals(StatusInfo(StatusKind.AWAY, "Отлучка"), statusOf(member(presence = Presence.AWAY)))
    }
}
