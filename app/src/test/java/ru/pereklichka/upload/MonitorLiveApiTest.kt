package ru.pereklichka.upload

import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import ru.pereklichka.di.NetworkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверяет боевой сервер по адресу из `MONITOR_LIVE_URL`. Время точек обрезается до миллисекунд, как их шлёт
 * телефон: сервер хранит время с точностью до микросекунд, а часы JVM на Windows дают сотни наносекунд.
 */
class MonitorLiveApiTest {

    @Test
    fun `production api accepts events and provides read-only access`() = runBlocking {
        val baseUrl = System.getenv("MONITOR_LIVE_URL")
        assumeTrue("MONITOR_LIVE_URL is not set", !baseUrl.isNullOrBlank())
        val url = EventsApi.eventsUrl(baseUrl!!)
        val api = NetworkModule.eventsApi(NetworkModule.okHttpClient())
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val authorization = EventsApi.bearer(Base64.getUrlEncoder().withoutPadding().encodeToString(random))
        val sender = UUID.randomUUID().toString()
        val member = UUID.randomUUID().toString()
        val recordedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
        val events = listOf(
            EventInputDto(UUID.randomUUID().toString(), member, EventTypeDto.LOCATION, recordedAt, LocationDataDto(43.3499, 42.4453, accuracyM = 0.0, altitudeM = 0.0, speedMps = 0.0, bearingDeg = 0.0, batteryPercent = 0), username = "Аня"),
            EventInputDto(UUID.randomUUID().toString(), member, EventTypeDto.LOCATION, recordedAt, LocationDataDto(43.3501, 42.4455, accuracyM = 8.5, altitudeM = 1200.0, speedMps = 1.5, bearingDeg = 90.0, batteryPercent = 100)),
        )

        val accepted = api.postEvents(url, authorization, EventBatchDto(sender, events))
        assertEquals(202, accepted.code())
        assertEquals(AcceptedDto(events.size), accepted.body())

        var first: EventPageDto? = null
        val deadline = System.nanoTime() + 60_000_000_000L
        while (first == null && System.nanoTime() < deadline) {
            val response = api.getEvents(url, authorization, limit = 1)
            if (response.code() == 200 && !response.body()?.events.isNullOrEmpty()) first = response.body()
            else {
                assertTrue(response.code() == 200 || response.code() == 404, "Unexpected status: ${response.code()}")
                delay(1_000)
            }
        }
        val page = assertNotNull(first, "Events were not available within 60 seconds")
        val cursor = assertNotNull(page.nextCursor)
        val second = api.getEvents(url, authorization, limit = 1, cursor = cursor)
        assertEquals(200, second.code())
        val next = assertNotNull(second.body())
        assertEquals(null, next.nextCursor)
        val stored = page.events + next.events
        assertEquals(events.map { it.id }.toSet(), stored.map { it.id }.toSet())
        for (input in events) {
            val actual = stored.single { it.id == input.id }
            assertEquals(input.memberId, actual.memberId)
            assertEquals(input.type, actual.type)
            assertEquals(Instant.parse(input.recordedAt), Instant.parse(actual.recordedAt))
            assertNotNull(Instant.parse(actual.receivedAt))
            assertEquals(input.data, actual.data)
            assertEquals(input.username, actual.username)
        }

        val share = api.createShareLink(EventsApi.shareLinksUrl(baseUrl), authorization)
        assertEquals(201, share.code())
        val view = assertNotNull(share.body()).token
        assertTrue(view.startsWith("view_"))
        val viewAuthorization = EventsApi.bearer(view)
        val read = api.getEvents(url, viewAuthorization)
        assertEquals(200, read.code())
        assertEquals(events.map { it.id }.toSet(), assertNotNull(read.body()).events.map { it.id }.toSet())
        assertEquals(401, api.postEvents(url, viewAuthorization, EventBatchDto(sender, emptyList())).code())
        assertEquals(401, api.createShareLink(EventsApi.shareLinksUrl(baseUrl), viewAuthorization).code())
    }
}
