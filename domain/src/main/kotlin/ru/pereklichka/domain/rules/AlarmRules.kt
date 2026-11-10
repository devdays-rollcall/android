package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind

/** О ком тревога: у ALARM это `target`, у SOS сам автор. У остальных событий null. */
internal val ControlEvent.alarmTarget: MemberId?
    get() = when (type) {
        EventType.ALARM -> target
        EventType.SOS -> author
        else -> null
    }

/**
 * Активные тревоги и SOS: те, которые не закрыл ни один действительный RESOLVE со ссылкой на них в `refs`.
 * Какой RESOLVE действителен, описано у [closes]. Вызывает [GroupCalculator].
 *
 * Результат зависит только от множества событий: порядок и повторы на него не влияют. Снятие, пришедшее
 * раньше тревоги, закрывает её, как только она появилась. Радиоконтакт тревогу не снимает.
 */
internal fun activeAlarms(events: Collection<ControlEvent>, leaderId: MemberId): List<ControlEvent> {
    val alarmsById = events.filter { it.alarmTarget != null }.associateBy { it.id }
    val closedIds = events
        .filter { it.type == EventType.RESOLVE }
        .flatMap { resolve -> resolve.refs.mapNotNull(alarmsById::get).filter { resolve.closes(it, leaderId) } }
        .map { it.id }
        .toSet()
    return alarmsById.values.filter { it.id !in closedIds }
}

/**
 * Снимает ли этот RESOLVE тревогу [alarm]. SELF_OK присылает сам человек, SEEN_OK кто угодно, кроме него,
 * FALSE_ALARM руководитель или автор этой самой тревоги. Цель снятия должна совпасть с целью тревоги.
 */
private fun ControlEvent.closes(alarm: ControlEvent, leaderId: MemberId): Boolean {
    val alarmTarget = alarm.alarmTarget
    if (target != alarmTarget) return false
    return when (resolveKind) {
        ResolveKind.SELF_OK -> author == alarmTarget
        ResolveKind.SEEN_OK -> author != alarmTarget
        ResolveKind.FALSE_ALARM -> author == leaderId || author == alarm.author
        null -> false
    }
}
