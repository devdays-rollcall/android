package ru.pereklichka.upload

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import ru.pereklichka.di.NetworkModule
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MonitorApiTest {

    private val server = MockWebServer()
    private val api = NetworkModule.eventsApi(NetworkModule.okHttpClient())

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `чтение событий передаёт ключ и параметры страницы`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"events":[{"id":"event-id","member_id":"member-id","type":"location","recorded_at":"2026-11-09T12:00:00Z","received_at":"2026-11-09T12:01:00Z","data":{"latitude":43.3,"longitude":42.4,"battery_percent":78,"extra":true}}],"next_cursor":"next cursor"}""",
        ).build())

        val response = api.getEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("view_key"), 25, "a+b /?")

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/events", request.url.encodedPath)
        assertEquals("Bearer view_key", request.headers["Authorization"])
        assertEquals("25", request.url.queryParameter("limit"))
        assertEquals("a+b /?", request.url.queryParameter("cursor"))
        assertEquals(200, response.code())
        val page = assertNotNull(response.body())
        assertEquals("next cursor", page.nextCursor)
        assertEquals(EventOutputDto("event-id", "member-id", EventTypeDto.LOCATION, "2026-11-09T12:00:00Z", "2026-11-09T12:01:00Z", LocationDataDto(43.3, 42.4, batteryPercent = 78)), page.events.single())
    }

    @Test
    fun `первая страница не передаёт параметры а пустой курсор ответа читается`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"events":[],"next_cursor":null}""").build())

        val response = api.getEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("group_key"))

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(request.url.query)
        assertEquals(EventPageDto(emptyList(), null), response.body())
    }

    @Test
    fun `создание ключа просмотра использует ключ группы`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("""{"token":"view_random"}""").build())

        val response = api.createShareLink(EventsApi.shareLinksUrl(server.url("/").toString()), EventsApi.bearer("group_key"))

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/share-links", request.url.encodedPath)
        assertEquals("Bearer group_key", request.headers["Authorization"])
        assertEquals(201, response.code())
        assertEquals(ShareTokenDto("view_random"), response.body())
    }

    @Test
    fun `ошибки сервера остаются доступны вызывающему коду`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"invalid_token","message":"Invalid token"}}""").build())
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":{"code":"unavailable","message":"Unavailable"}}""").build())

        val unauthorized = api.createShareLink(EventsApi.shareLinksUrl(server.url("/").toString()), EventsApi.bearer("bad"))
        val unavailable = api.getEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("key"))

        assertEquals(401, unauthorized.code())
        assertNull(unauthorized.body())
        assertEquals("""{"error":{"code":"invalid_token","message":"Invalid token"}}""", unauthorized.errorBody()?.string())
        assertEquals(503, unavailable.code())
        assertNull(unavailable.body())
        assertNotNull(unavailable.errorBody())
    }

    @Test
    fun `ошибка отправки пачки возвращает код и тело ответа`() = runTest {
        server.enqueue(MockResponse.Builder().code(422).body("""{"error":{"code":"invalid_event","message":"Invalid event"}}""").build())

        val response = api.postEvents(EventsApi.eventsUrl(server.url("/").toString()), EventsApi.bearer("key"), EventBatchDto("sender", emptyList()))

        assertEquals(422, response.code())
        assertEquals("""{"error":{"code":"invalid_event","message":"Invalid event"}}""", response.errorBody()?.string())
    }

    @Test
    fun `сетевая ошибка передаётся вызывающему коду`() = runTest {
        val url = EventsApi.eventsUrl(server.url("/").toString())
        server.close()

        assertFailsWith<IOException> {
            api.getEvents(url, EventsApi.bearer("key"))
        }
    }
}
