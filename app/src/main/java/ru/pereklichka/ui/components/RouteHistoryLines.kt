package ru.pereklichka.ui.components

import ru.pereklichka.data.PastRoute
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.time.Stamped
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d MMMM", RU)
private val DAY_WITH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU)

/** Строка истории прошлого маршрута: когда по часам этого телефона, что случилось и подробность, если есть. */
data class HistoryLine(val atWallMs: Long, val title: String, val detail: String? = null)

/**
 * Когда событие случилось по часам этого телефона: время приёма минус возраст записи при приёме. Часы автора
 * в походе могут расходиться с моими, поэтому его `createdAtWallMs` для истории не годится.
 */
fun Stamped<ControlEvent>.happenedAtWallMs(): Long = receivedAtWallMs - ageAtReceiptMs

/**
 * История маршрута по событиям группы, по времени: создание группы и вступления, начало маршрута, переклички
 * с числом ответивших, отлучки, тревоги и SOS, их снятие, выходы и завершение. Ответы на перекличку отдельных строк
 * не дают, их число стоит в строке переклички. События начала, переклички и завершения считаются только
 * от руководителя [leaderId], как в `GroupCalculator`. Себя [me] история называет «вы».
 */
fun historyLines(events: List<Stamped<ControlEvent>>, leaderId: MemberId, me: MemberId): List<HistoryLine> {
    val all = events.map { it.value }.distinctBy { it.id }
    val names = all.filter { it.type == EventType.JOINED }
        .groupBy { it.author }
        .mapValues { (_, joined) -> joined.maxBy { it.seq }.name }
    fun nameOf(id: MemberId?): String = when (id) {
        null -> "участник"
        me -> "вы"
        else -> names[id] ?: "участник"
    }
    val answers = all.filter { it.type == EventType.ROLLCALL_ANSWER }
    val started = all.any { it.author == leaderId && it.type == EventType.ROUTE_STARTED }
    val sos = all.filter { it.type == EventType.SOS }.map { it.id }.toSet()

    return events.distinctBy { it.value.id }
        .sortedWith(compareBy({ it.happenedAtWallMs() }, { it.value.author.value }, { it.value.seq }))
        .mapNotNull { stamped ->
            val event = stamped.value
            val at = stamped.happenedAtWallMs()
            val fromLeader = event.author == leaderId
            when (event.type) {
                EventType.JOINED -> if (fromLeader) {
                    HistoryLine(at, "Группа создана", nameOf(event.author))
                } else {
                    HistoryLine(at, "Вступление в группу", nameOf(event.author))
                }
                EventType.ROUTE_STARTED -> HistoryLine(at, "Маршрут начат").takeIf { fromLeader }
                EventType.ROLLCALL -> if (fromLeader) {
                    val answered = answers.filter { event.id in it.refs }.map { it.author }.distinct().size
                    HistoryLine(at, "Перекличка", "${plural(answered, "ответил", "ответили", "ответили")} $answered")
                } else {
                    null
                }
                EventType.AWAY -> {
                    val minutes = (event.durationMs ?: 0) / 60_000
                    val title = if (minutes > 0) "Отлучка на $minutes мин" else "Отлучка отменена"
                    HistoryLine(at, title, nameOf(event.author))
                }
                EventType.ALARM -> {
                    val raisedBy = if (event.author == me) "объявили вы" else "объявил(а) ${nameOf(event.author)}"
                    HistoryLine(at, "Тревога: ${nameOf(event.target)}", raisedBy)
                }
                EventType.SOS -> HistoryLine(at, "SOS: ${nameOf(event.author)}")
                EventType.RESOLVE -> {
                    val what = if (event.refs.any { it in sos }) "SOS снят" else "Тревога снята"
                    val how = when (event.resolveKind) {
                        ResolveKind.SELF_OK -> "«Я в порядке»"
                        ResolveKind.SEEN_OK -> "«Я рядом, помощь не нужна»"
                        ResolveKind.FALSE_ALARM -> "по ошибке"
                        null -> null
                    }
                    HistoryLine(at, "$what: ${nameOf(event.target)}", listOfNotNull(nameOf(event.author), how).joinToString(", "))
                }
                EventType.LEFT -> HistoryLine(at, "Выход из группы", nameOf(event.author)).takeIf { !fromLeader }
                EventType.ROUTE_FINISHED -> HistoryLine(at, if (started) "Маршрут завершён" else "Сбор отменён").takeIf { fromLeader }
                EventType.ROLLCALL_ANSWER, EventType.UNKNOWN -> null
            }
        }
}

/**
 * Чем кончился прошлый маршрут: «Вы вышли из группы», «Сбор отменён», «Маршрут завершён», «Маршрут не завершён»
 * (руководитель не завершил его, пока телефон был в группе) или «Маршрут не начат».
 */
fun pastRouteOutcome(route: PastRoute): String = when {
    route.iLeft -> "Вы вышли из группы"
    route.finishedAtWallMs != null -> if (route.startedAtWallMs == null) "Сбор отменён" else "Маршрут завершён"
    route.startedAtWallMs != null -> "Маршрут не завершён"
    else -> "Маршрут не начат"
}

/** Сколько шёл маршрут от «Выходим» до завершения, «2 ч 14 мин»; null, если он не начат или не завершён. */
fun pastRouteDuration(route: PastRoute): String? {
    val started = route.startedAtWallMs ?: return null
    val finished = route.finishedAtWallMs ?: return null
    return formatDuration((finished - started).coerceAtLeast(0))
}

/**
 * Подпись прошлого маршрута в списке: день, сколько шёл маршрут или чем он кончился и что с треком,
 * «12 ноября · 2 ч 14 мин · трек отправлен». День берётся от начала маршрута, а без него от создания группы.
 */
fun pastRouteSubtitle(route: PastRoute, track: String?, nowWallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = formatDay(route.startedAtWallMs ?: route.group.createdAtWallMs, nowWallMs, zone)
    val how = pastRouteDuration(route)?.takeIf { !route.iLeft } ?: pastRouteOutcome(route).replaceFirstChar(Char::lowercase)
    return listOfNotNull(day, how, track).joinToString(" · ")
}

/** «12 ноября», а для прошлых лет «12 ноября 2025». */
fun formatDay(wallMs: Long, nowWallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(wallMs).atZone(zone)
    val thisYear = Instant.ofEpochMilli(nowWallMs).atZone(zone).year == date.year
    return (if (thisYear) DAY else DAY_WITH_YEAR).format(date)
}

/** «10:42» по часам этого телефона. */
fun formatTime(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME.format(Instant.ofEpochMilli(wallMs).atZone(zone))
