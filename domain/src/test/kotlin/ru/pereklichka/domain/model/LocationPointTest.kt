package ru.pereklichka.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверка точки перед отправкой на сервер: диапазоны из `openapi.yaml`, NaN, бесконечности и UUID в id. */
class LocationPointTest {

    private val valid = LocationPoint(
        id = EventId("p1"),
        author = MemberId("bob"),
        seq = 1,
        latitude = 43.35,
        longitude = 42.44,
        recordedAtMs = 0,
        accuracyM = 8.5,
        altitudeM = -20.0,
        speedMps = 0.0,
        bearingDeg = 359.9,
        batteryPercent = 100,
    )

    @Test
    fun `точка с допустимыми значениями проходит проверку`() {
        assertTrue(valid.hasValidValues())
        assertTrue(valid.copy(latitude = -90.0, longitude = 180.0, bearingDeg = 0.0, batteryPercent = 0).hasValidValues())
    }

    @Test
    fun `необязательные поля можно не заполнять`() {
        val minimal = valid.copy(accuracyM = null, altitudeM = null, speedMps = null, bearingDeg = null, batteryPercent = null)

        assertTrue(minimal.hasValidValues())
    }

    @Test
    fun `значения вне диапазонов сервера не проходят`() {
        val invalid = listOf(
            valid.copy(latitude = 90.1),
            valid.copy(latitude = Double.NaN),
            valid.copy(longitude = -180.1),
            valid.copy(accuracyM = -1.0),
            valid.copy(accuracyM = Double.POSITIVE_INFINITY),
            valid.copy(altitudeM = Double.NaN),
            valid.copy(speedMps = -0.1),
            valid.copy(bearingDeg = 360.0),
            valid.copy(bearingDeg = Double.NaN),
            valid.copy(batteryPercent = 101),
            valid.copy(batteryPercent = -1),
        )

        invalid.forEach { assertFalse(it.hasValidValues(), "$it") }
    }

    @Test
    fun `на сервер уходят только точки с id и автором в виде UUID`() {
        val uploadable = valid.copy(
            id = EventId("d8a6b9f0-32b1-4b1d-a186-a86f2e401a11"),
            author = MemberId("3d783127-6f1d-454d-b99f-dd41f88f51dd"),
        )

        assertTrue(uploadable.isUploadable())
        assertFalse(valid.isUploadable())
        assertFalse(uploadable.copy(author = MemberId("1-2-3-4-5")).isUploadable())
        assertFalse(uploadable.copy(latitude = 90.1).isUploadable())
    }
}
