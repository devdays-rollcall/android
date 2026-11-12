package ru.pereklichka.ui.settings

import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.time.Age
import ru.pereklichka.ui.components.formatAge
import ru.pereklichka.ui.components.plural
import java.util.Locale

/** «3 с назад» до минуты, дальше «2 мин назад». */
fun formatAgoMs(ms: Long): String = if (ms < 60_000) "${ms.coerceAtLeast(0) / 1000} с назад" else formatAge(Age(ms, approximate = false))

/**
 * Число с неразрывным пробелом между разрядами: «1 240». Разделитель задан явно, потому что у русской локали
 * он зависит от версии Java.
 */
fun formatCount(n: Int): String = String.format(Locale.ROOT, "%,d", n).replace(',', NBSP)

private val NBSP = Char(0xA0)

/** Заголовок строки «Рядом N телефонов»: [count] соседей, которых телефон слышит напрямую сейчас. */
fun nearbyTitle(count: Int): String =
    if (count == 0) "Рядом никого" else "Рядом $count ${plural(count, "телефон", "телефона", "телефонов")}"

/**
 * Подпись под «Рядом N телефонов»: имена [nearby] и возраст последнего сообщения от кого-нибудь из группы
 * ([lastMessageAgoMs], null, если сообщений не было).
 */
fun nearbySubtitle(nearby: List<String>, lastMessageAgoMs: Long?): String = when {
    lastMessageAgoMs == null -> "Сообщений от группы не было"
    nearby.isEmpty() -> "Последнее сообщение ${formatAgoMs(lastMessageAgoMs)}"
    else -> "${nearby.joinToString()} · последнее сообщение ${formatAgoMs(lastMessageAgoMs)}"
}

/**
 * Подпись под «Картой похода». [sent] отправлено на сервер, [waiting] ждут отправки. Без адреса сервера точки
 * копятся в телефоне, а ошибку отправки [error] показываем вместо числа отправленных.
 */
fun mapSubtitle(sent: Int, waiting: Int, serverSet: Boolean, error: String?): String {
    val waitingPart = "ждут ${formatCount(waiting)}"
    return when {
        !serverSet -> "Адрес сервера не задан · $waitingPart"
        error != null -> "$error · $waitingPart"
        else -> "${plural(sent, "Отправлена", "Отправлено", "Отправлено")} ${formatCount(sent)} " +
            "${plural(sent, "точка", "точки", "точек")} · $waitingPart"
    }
}

/**
 * Подпись соседа на экране «Для разработчиков». [heardAgoMs] из `PresenceTracker` (null, если напрямую не слышали
 * последние 10 минут), [groupHeard] значит, что о нём есть хоть какой-то контакт через других.
 */
fun neighbourSubtitle(heardAgoMs: Long?, groupHeard: Boolean): String = when {
    heardAgoMs != null && PresenceTracker.isHeardNow(heardAgoMs) -> "слышим напрямую · ${formatAgoMs(heardAgoMs)}"
    heardAgoMs != null -> "слышали напрямую ${formatAgoMs(heardAgoMs)}"
    groupHeard -> "только через посредника"
    else -> "не слышно"
}
