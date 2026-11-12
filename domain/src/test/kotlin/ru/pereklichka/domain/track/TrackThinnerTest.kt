package ru.pereklichka.domain.track

import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

/** Прореживание трека: при фиксациях раз в 5 с в трек идёт смещение на 15 м или прошедшая минута. */
class TrackThinnerTest {

    private val start = LatLon(43.35, 42.44)

    /** Десять минут на месте, фиксации каждые 5 с с дрожанием в пару метров. */
    @Test
    fun `стоящий человек даёт точку раз в минуту`() {
        val thinner = TrackThinner()
        val fixes = (0..120).map { i -> fix(i * 5_000L, northM = if (i % 2 == 0) 0.0 else 2.0) }

        val kept = fixes.filter(thinner::shouldKeep)

        assertEquals((0..10).map { it * 60_000L }, kept.map { it.elapsedRealtimeMs })
    }

    /** Человек идёт со скоростью 1,2 м/с и каждые 5 с уходит на 6 м, поэтому в трек идёт каждая третья фиксация (18 м). */
    @Test
    fun `идущий человек даёт точку каждые 15 метров`() {
        val thinner = TrackThinner()
        val fixes = (0..60).map { i -> fix(i * 5_000L, northM = i * 6.0) }

        val kept = fixes.filter(thinner::shouldKeep)

        assertEquals((0..60 step 3).map { it * 6.0 }, kept.map { northOf(it) })
    }

    /** Настенное время прыгает на час назад, а монотонное идёт ровно. */
    @Test
    fun `перевод часов телефона на решение не влияет`() {
        val thinner = TrackThinner()
        val fixes = (0..24).map { i -> fix(i * 5_000L, northM = 0.0, wallJumpMs = if (i > 6) -3_600_000 else 0) }

        val kept = fixes.filter(thinner::shouldKeep)

        assertEquals(listOf(0L, 60_000L, 120_000L), kept.map { it.elapsedRealtimeMs })
    }

    /** При точности 80 м скачки на 30 м у стоящего человека смещением не считаются. */
    @Test
    fun `неточная фиксация идёт в трек только по времени`() {
        val thinner = TrackThinner()
        val fixes = (0..24).map { i -> fix(i * 5_000L, northM = if (i % 2 == 0) 0.0 else 30.0, accuracyM = 80.0) }

        val kept = fixes.filter(thinner::shouldKeep)

        assertEquals(listOf(0L, 60_000L, 120_000L), kept.map { it.elapsedRealtimeMs })
    }

    private fun fix(elapsedMs: Long, northM: Double, wallJumpMs: Long = 0, accuracyM: Double = 5.0) = Fix(
        position = LatLon(start.latitude + northM / METERS_PER_DEGREE, start.longitude),
        accuracyM = accuracyM,
        altitudeM = null,
        speedMps = null,
        bearingDeg = null,
        timeUtcMs = 1_794_300_000_000 + elapsedMs + wallJumpMs,
        elapsedRealtimeMs = elapsedMs,
    )

    private fun northOf(fix: Fix): Double = Math.round((fix.position.latitude - start.latitude) * METERS_PER_DEGREE).toDouble()

    private companion object {
        /** Метров в градусе широты при радиусе Земли 6 371 000 м. */
        const val METERS_PER_DEGREE = 6_371_000 * Math.PI / 180
    }
}
