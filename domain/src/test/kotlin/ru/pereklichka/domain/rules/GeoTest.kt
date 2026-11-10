package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {

    private val origin = LatLon(43.0, 42.0)

    @Test
    fun `сотая градуса широты это 1112 метров`() {
        assertEquals(1_111.95, Geo.distanceM(origin, LatLon(43.01, 42.0)), 0.01)
        assertEquals(0.0, Geo.distanceM(origin, origin), 1e-9)
    }

    @Test
    fun `азимут считается от севера по часовой стрелке`() {
        assertEquals(0.0, Geo.bearingDeg(origin, LatLon(43.01, 42.0)), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(origin, LatLon(43.0, 42.01)), 0.01)
        assertEquals(180.0, Geo.bearingDeg(origin, LatLon(42.99, 42.0)), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(origin, LatLon(43.0, 41.99)), 0.01)
    }
}
