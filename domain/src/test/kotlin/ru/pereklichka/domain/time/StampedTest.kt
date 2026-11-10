package ru.pereklichka.domain.time

import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals

class StampedTest {

    private var nowMs = 100_000L
    private val clock = FakeClock { nowMs }

    @Test
    fun `возраст растёт по монотонным часам`() {
        val stamped = Stamped.received("запись", ageMs = 4_000, clock)

        nowMs += 6_000

        assertEquals(Age(10_000, approximate = false), stamped.ageNow(clock))
    }

    @Test
    fun `перевод настенных часов не влияет на возраст`() {
        val stamped = Stamped.received("запись", ageMs = 0, clock)

        nowMs += 1_000
        clock.wallShiftMs = -3_600_000

        assertEquals(Age(1_000, approximate = false), stamped.ageNow(clock))
    }

    @Test
    fun `после перезагрузки возраст считается по настенным часам и помечается примерным`() {
        val stamped = Stamped.received("запись", ageMs = 2_000, clock)

        nowMs += 30_000
        clock.reboot()
        nowMs += 5_000

        assertEquals(Age(37_000, approximate = true), stamped.ageNow(clock))
    }

    @Test
    fun `после перезагрузки и перевода часов назад возраст не уменьшается`() {
        val stamped = Stamped.received("запись", ageMs = 2_000, clock)

        clock.reboot()
        clock.wallShiftMs = -600_000

        assertEquals(Age(2_000, approximate = true), stamped.ageNow(clock))
    }

    @Test
    fun `возраст из сети ограничен нулём и сутками`() {
        assertEquals(0, Stamped.received("запись", ageMs = -500, clock).ageAtReceiptMs)
        assertEquals(Stamped.MAX_AGE_MS, Stamped.received("запись", ageMs = Long.MAX_VALUE, clock).ageAtReceiptMs)
    }

    @Test
    fun `своя запись получает возраст ноль и текущие часы`() {
        val stamped = Stamped.own("запись", clock)

        assertEquals(Stamped("запись", 0, clock.elapsedMs(), clock.bootId(), clock.wallMs()), stamped)
    }
}
