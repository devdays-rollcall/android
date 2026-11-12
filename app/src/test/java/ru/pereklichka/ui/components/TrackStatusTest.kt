package ru.pereklichka.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Строка о треке на стартовой карточке и экране завершения. */
class TrackStatusTest {

    @Test
    fun `строка о треке по числу точек и адресу сервера`() {
        assertNull(trackLine(TrackStatus(points = 0, unsent = 0, hasServer = true)))
        assertEquals("трек отправлен", trackLine(TrackStatus(points = 40, unsent = 0, hasServer = true)))
        assertEquals("12 точек трека ждут интернета", trackLine(TrackStatus(points = 40, unsent = 12, hasServer = true)))
        assertEquals("1 точка трека ждёт интернета", trackLine(TrackStatus(points = 40, unsent = 1, hasServer = true)))
        assertEquals(
            "адрес сервера не задан, трек не отправляется",
            trackLine(TrackStatus(points = 40, unsent = 3, hasServer = false)),
        )
    }
}
