package ru.pereklichka.ui.components

import ru.pereklichka.data.PastRoute
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.time.Stamped
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** История прошлого маршрута и подписи в списке прошлых маршрутов. */
class RouteHistoryLinesTest {

    private val leader = MemberId("leader")
    private val olga = MemberId("olga")
    private val me = MemberId("me")
    private val zone = ZoneId.of("Europe/Moscow")

    /** 12 ноября 2026 года, 10:00 по Москве. */
    private val start = 1_794_466_800_000

    private var seq = 0L

    /** JOINED руководителя пришёл от соседа с возрастом 5 минут: случился в 10:00, хотя принят в 10:05. */
    @Test
    fun `история идёт по времени этого телефона и называет меня «вы»`() {
        val events = listOf(
            event(leader, EventType.JOINED, at = start + 300_000, age = 300_000, name = "Никита"),
            event(me, EventType.JOINED, at = start + 60_000, name = "Аня"),
            event(olga, EventType.JOINED, at = start + 120_000, name = "Ольга"),
            event(leader, EventType.ROUTE_STARTED, at = start + 600_000),
        )

        val lines = historyLines(events, leader, me)

        assertEquals(
            listOf(
                HistoryLine(start, "Группа создана", "Никита"),
                HistoryLine(start + 60_000, "Вступление в группу", "вы"),
                HistoryLine(start + 120_000, "Вступление в группу", "Ольга"),
                HistoryLine(start + 600_000, "Маршрут начат"),
            ),
            lines,
        )
    }

    @Test
    fun `перекличка показывает число ответивших, а ответы своих строк не дают`() {
        val rollcall = event(leader, EventType.ROLLCALL, at = start)
        val events = listOf(
            rollcall,
            event(olga, EventType.ROLLCALL_ANSWER, at = start + 10_000, refs = listOf(rollcall.value.id)),
            event(me, EventType.ROLLCALL_ANSWER, at = start + 20_000, refs = listOf(rollcall.value.id)),
        )

        assertEquals(listOf(HistoryLine(start, "Перекличка", "ответили 2")), historyLines(events, leader, me))
    }

    @Test
    fun `тревоги, SOS, отлучки и выход`() {
        val sos = event(olga, EventType.SOS, at = start + 30_000)
        val events = listOf(
            event(leader, EventType.JOINED, at = start, name = "Никита"),
            event(olga, EventType.JOINED, at = start, name = "Ольга"),
            event(me, EventType.ALARM, at = start + 10_000, target = olga),
            event(olga, EventType.AWAY, at = start + 20_000, durationMs = 10 * 60_000),
            sos,
            event(leader, EventType.RESOLVE, at = start + 40_000, target = olga, refs = listOf(sos.value.id),
                resolveKind = ResolveKind.SEEN_OK),
            event(olga, EventType.AWAY, at = start + 50_000, durationMs = 0),
            event(olga, EventType.LEFT, at = start + 60_000),
        )

        val lines = historyLines(events, leader, me).drop(2)

        assertEquals(
            listOf(
                HistoryLine(start + 10_000, "Тревога: Ольга", "объявили вы"),
                HistoryLine(start + 20_000, "Отлучка на 10 мин", "Ольга"),
                HistoryLine(start + 30_000, "SOS: Ольга"),
                HistoryLine(start + 40_000, "SOS снят: Ольга", "Никита, «Я рядом, помощь не нужна»"),
                HistoryLine(start + 50_000, "Отлучка отменена", "Ольга"),
                HistoryLine(start + 60_000, "Выход из группы", "Ольга"),
            ),
            lines,
        )
    }

    @Test
    fun `завершение без начала это отменённый сбор, а события не от руководителя не считаются`() {
        val events = listOf(
            event(olga, EventType.ROUTE_STARTED, at = start),
            event(leader, EventType.ROUTE_FINISHED, at = start + 60_000),
            event(olga, EventType.ROUTE_FINISHED, at = start + 70_000),
        )

        assertEquals(listOf(HistoryLine(start + 60_000, "Сбор отменён")), historyLines(events, leader, me))
    }

    @Test
    fun `подпись прошлого маршрута называет день, сколько он шёл, и трек`() {
        val route = route(startedAt = start, finishedAt = start + 8_040_000)

        assertEquals("12 ноября · 2 ч 14 мин · трек отправлен", pastRouteSubtitle(route, "трек отправлен", start, zone))
        assertEquals("12 ноября 2026 · 2 ч 14 мин", pastRouteSubtitle(route, null, start + 400 * 86_400_000L, zone))
    }

    @Test
    fun `подпись говорит, чем кончился незавершённый маршрут`() {
        assertEquals("12 ноября · вы вышли из группы", pastRouteSubtitle(route(start, start + 60_000, iLeft = true), null, start, zone))
        assertEquals("12 ноября · сбор отменён", pastRouteSubtitle(route(null, start), null, start, zone))
        assertEquals("12 ноября · маршрут не завершён", pastRouteSubtitle(route(start, null), null, start, zone))
        assertEquals("12 ноября · маршрут не начат", pastRouteSubtitle(route(null, null), null, start, zone))
    }

    private fun route(startedAt: Long?, finishedAt: Long?, iLeft: Boolean = false) = PastRoute(
        GroupInfo("tag", "token", "Эльбрус", leader, me, createdAtWallMs = start),
        startedAtWallMs = startedAt,
        finishedAtWallMs = finishedAt,
        members = 3,
        points = 0,
        unsentPoints = 0,
        iLeft = iLeft,
    )

    private fun event(
        author: MemberId,
        type: EventType,
        at: Long,
        age: Long = 0,
        name: String? = null,
        target: MemberId? = null,
        refs: List<EventId> = emptyList(),
        durationMs: Long? = null,
        resolveKind: ResolveKind? = null,
    ): Stamped<ControlEvent> = Stamped(
        ControlEvent(EventId("e${++seq}"), author, seq, createdAtWallMs = 0, type, target, refs, name, durationMs, resolveKind),
        ageAtReceiptMs = age,
        receivedAtElapsedMs = 0,
        receivedBootId = "1",
        receivedAtWallMs = at,
    )
}
