package ru.pereklichka.service

import android.app.Application
import android.location.Location
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.toLocationPoint
import ru.pereklichka.upload.toEventInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FusedLocationSourceTest {
    @Test
    fun `all measured fields reach the event`() {
        val location = Location("gps").apply {
            latitude = 43.35
            longitude = 42.44
            accuracy = 8.5f
            altitude = 1200.25
            speed = 1.5f
            bearing = 90f
            time = 1_794_300_000_000
            elapsedRealtimeNanos = 12_000_000_000
        }
        val fix = location.toFix()
        val event = fix.toLocationPoint(EventId("id"), MemberId("member"), 1, 78).toEventInput()

        assertEquals(12_000, fix.elapsedRealtimeMs)
        assertEquals("member", event.memberId)
        assertEquals(43.35, event.data.latitude)
        assertEquals(42.44, event.data.longitude)
        assertEquals(8.5, event.data.accuracyM)
        assertEquals(1200.25, event.data.altitudeM)
        assertEquals(1.5, event.data.speedMps)
        assertEquals(90.0, event.data.bearingDeg)
        assertEquals(78, event.data.batteryPercent)
        assertEquals("2026-11-10T08:40:00Z", event.recordedAt)
    }

    @Test
    fun `missing measurements remain absent`() {
        val fix = Location("gps").toFix()
        assertNull(fix.accuracyM)
        assertNull(fix.altitudeM)
        assertNull(fix.speedMps)
        assertNull(fix.bearingDeg)
    }
}
