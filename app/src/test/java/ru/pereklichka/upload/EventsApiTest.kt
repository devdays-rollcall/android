package ru.pereklichka.upload

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import ru.pereklichka.di.NetworkModule
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Тело запроса сверено с `EventBatch` из `docs/server/openapi.yaml`: имена полей, отсутствующие поля, тип и время. */
class EventsApiTest {

    private val server = MockWebServer()
    private val api = NetworkModule.eventsApi(NetworkModule.okHttpClient())

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `пачка уходит в snake_case без пустых полей и с типом location`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"accepted":1}""").build())
        val point = LocationPoint(
            id = EventId("d8a6b9f0-32b1-4b1d-a186-a86f2e401a11"),
            author = MemberId("5b1f0c2e-7a44-4e0f-9b0d-2d8b6f1c9a10"),
            seq = 1,
            latitude = 43.3499,
            longitude = 42.4453,
            recordedAtMs = 1_794_225_600_120,
            accuracyM = 8.5,
            batteryPercent = 78,
        )
        val batch = EventBatchDto(senderId = "c9c7458e-3d19-4e20-ae5d-dbc3ec6f4af1", events = listOf(point.toEventInput()))

        val response = api.postEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("token"), batch)

        val request = server.takeRequest()
        assertEquals("/api/v1/events", request.url.encodedPath)
        assertEquals("Bearer token", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val expected = Json.parseToJsonElement(
            """
            {"sender_id":"c9c7458e-3d19-4e20-ae5d-dbc3ec6f4af1","events":[{
              "id":"d8a6b9f0-32b1-4b1d-a186-a86f2e401a11","member_id":"5b1f0c2e-7a44-4e0f-9b0d-2d8b6f1c9a10",
              "type":"location","recorded_at":"2026-11-09T12:00:00.120Z",
              "data":{"latitude":43.3499,"longitude":42.4453,"accuracy_m":8.5,"battery_percent":78}}]}
            """,
        )
        assertEquals(expected, body)
        assertEquals(202, response.code())
        assertEquals(AcceptedDto(1), response.body())
    }

    @Test
    fun `пустая пачка для heartbeat`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"accepted":0}""").build())

        api.postEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("token"), EventBatchDto("me", emptyList()))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals(0, body.getValue("events").jsonArray.size)
    }
}
