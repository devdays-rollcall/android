package ru.pereklichka.domain.presence

import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Кого телефон слышит напрямую: порог 30 с, окно 10 минут для `heardAgoMs`, моменты по монотонным часам. */
class PresenceTrackerTest {

    private var nowMs = 0L
    private val tracker = PresenceTracker(FakeClock { nowMs })
    private val bob = MemberId("bob")
    private val eve = MemberId("eve")

    @Test
    fun `сосед слышен 30 секунд после последнего сообщения`() {
        tracker.onHeard(bob)

        nowMs += PresenceTracker.HEARD_NOW_MS
        assertTrue(tracker.isHeardNow(bob))

        nowMs += 1
        assertFalse(tracker.isHeardNow(bob))
    }

    @Test
    fun `новое сообщение продлевает присутствие`() {
        tracker.onHeard(bob)
        nowMs += 25_000
        tracker.onHeard(bob)
        nowMs += 25_000

        assertTrue(tracker.isHeardNow(bob))
    }

    @Test
    fun `того, кого не слышали, нет`() {
        assertFalse(tracker.isHeardNow(bob))
        assertEquals(emptyMap(), tracker.heardAgo())
    }

    @Test
    fun `heardAgo отдаёт давность за последние 10 минут`() {
        tracker.onHeard(eve)
        nowMs += PresenceTracker.HEARD_AGO_WINDOW_MS - 2_000
        tracker.onHeard(bob)
        nowMs += 2_000

        assertEquals(mapOf(bob to 2_000L, eve to PresenceTracker.HEARD_AGO_WINDOW_MS), tracker.heardAgo())

        nowMs += 1
        assertEquals(mapOf(bob to 2_001L), tracker.heardAgo())
    }

    @Test
    fun `lastHeard хранит момент по монотонным часам`() {
        nowMs = 7_000
        tracker.onHeard(bob)

        assertEquals(mapOf(bob to 7_000L), tracker.lastHeard.value)
    }
}
