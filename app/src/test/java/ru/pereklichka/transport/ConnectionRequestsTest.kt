package ru.pereklichka.transport

import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Когда транспорт звонит соседу и когда повторяет запрос: запрос в полёте, потерянный телефон, отказ соседа. */
class ConnectionRequestsTest {

    private val requests = ConnectionRequests()
    private val endpoint = "E1"
    private val ioError = ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR

    @Test
    fun `пока запрос ждёт результата, второй не планируется`() {
        assertTrue(requests.onFound(endpoint))
        assertTrue(requests.tryStart(endpoint))

        assertFalse(requests.onFound(endpoint))
        assertFalse(requests.tryStart(endpoint))
    }

    @Test
    fun `после неудачи пауза растёт, после соединения начинается заново`() {
        requests.tryStart(endpoint)
        assertEquals(2_000L, requests.onFailure(endpoint, ioError))
        requests.tryStart(endpoint)
        assertEquals(4_000L, requests.onFailure(endpoint, null))

        requests.tryStart(endpoint)
        requests.onConnected(endpoint)
        requests.tryStart(endpoint)
        assertEquals(2_000L, requests.onFailure(endpoint, ioError))
    }

    @Test
    fun `чужой запрос не повторяем`() {
        assertNull(requests.onFailure(endpoint, ioError))
    }

    @Test
    fun `потерянному телефону не звоним, пока он не найдётся снова`() {
        requests.tryStart(endpoint)
        requests.onLost(endpoint)
        assertNull(requests.onFailure(endpoint, ioError))

        requests.onFound(endpoint)
        requests.tryStart(endpoint)
        assertEquals(2_000L, requests.onFailure(endpoint, ioError))
    }

    @Test
    fun `после отказа соседа ждём следующего onEndpointFound`() {
        requests.tryStart(endpoint)
        assertNull(requests.onFailure(endpoint, ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))

        assertTrue(requests.onFound(endpoint))
        assertTrue(requests.tryStart(endpoint))
    }

    @Test
    fun `забытый запрос не мешает следующему`() {
        requests.tryStart(endpoint)
        requests.forget(endpoint)

        assertTrue(requests.onFound(endpoint))
        assertNull(requests.onFailure(endpoint, ioError))
    }
}
