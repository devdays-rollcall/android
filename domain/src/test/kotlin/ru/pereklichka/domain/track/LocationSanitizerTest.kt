package ru.pereklichka.domain.track

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.toLocationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Чистка фиксации: недопустимые необязательные поля пропадают, фиксация без координат отбрасывается. */
class LocationSanitizerTest {

    private val sanitizer = LocationSanitizer()
    private val valid = Fix(LatLon(43.35, 42.44), 8.5, 2_100.0, 1.2, 90.0, timeUtcMs = 1_794_300_000_000, elapsedRealtimeMs = 0)

    @Test
    fun `допустимая фиксация не меняется`() {
        assertEquals(valid, sanitizer.sanitize(valid))
    }

    @Test
    fun `фиксация без допустимых широты и долготы не сохраняется`() {
        assertNull(sanitizer.sanitize(valid.copy(position = LatLon(Double.NaN, 42.44))))
        assertNull(sanitizer.sanitize(valid.copy(position = LatLon(43.35, 180.5))))
    }

    @Test
    fun `недопустимые необязательные значения пропадают, а не становятся нулём`() {
        val dirty = valid.copy(accuracyM = -1.0, altitudeM = Double.NaN, speedMps = Double.POSITIVE_INFINITY, bearingDeg = Double.NaN)

        val clean = sanitizer.sanitize(dirty)

        assertEquals(valid.copy(accuracyM = null, altitudeM = null, speedMps = null, bearingDeg = null), clean)
    }

    @Test
    fun `азимут приводится к полуинтервалу от 0 до 360`() {
        assertEquals(0.0, sanitizer.sanitize(valid.copy(bearingDeg = 360.0))?.bearingDeg)
        assertEquals(350.0, sanitizer.sanitize(valid.copy(bearingDeg = -10.0))?.bearingDeg)
        assertEquals(0.0, sanitizer.sanitize(valid.copy(bearingDeg = -1e-14))?.bearingDeg)
    }

    @Test
    fun `точка из очищенной фиксации проходит проверку сервера`() {
        val dirty = valid.copy(accuracyM = Double.NaN, speedMps = -3.0, bearingDeg = 720.0)

        val point = sanitizer.sanitize(dirty)!!.toLocationPoint(EventId("p"), MemberId("me"), 1, batteryPercent = 50)

        assertTrue(point.hasValidValues())
    }
}
