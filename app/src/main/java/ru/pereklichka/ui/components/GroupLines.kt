package ru.pereklichka.ui.components

import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RollcallView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.time.Age

/** Заряд не выше этого показывается в списке группы и выделяется в карточке участника. */
const val LOW_BATTERY_PERCENT = 20

private val SIDES_TOWARDS = listOf(
    "на север", "на северо-восток", "на восток", "на юго-восток", "на юг", "на юго-запад", "на запад", "на северо-запад",
)

/** Длительность без секунд: «меньше минуты», «14 мин», «2 ч 14 мин», «3 ч». */
fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "меньше минуты"
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0L -> "${minutes / 60} ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }
}

/** Оставшееся время по секундам, «2:00». Отрицательное считается нулём. */
fun formatClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Плашка под названием группы: «Сбор», «На маршруте 2 ч 14 мин», «Маршрут завершён». */
fun routeChipText(route: RouteState, startedAgo: Age?): String = when (route) {
    RouteState.LOBBY -> "Сбор"
    RouteState.FINISHED -> "Маршрут завершён"
    RouteState.ACTIVE -> when {
        startedAgo == null -> "На маршруте"
        startedAgo.approximate -> "На маршруте примерно ${formatDuration(startedAgo.ms)}"
        else -> "На маршруте ${formatDuration(startedAgo.ms)}"
    }
}

/**
 * Строка статуса под именем в списке группы, без заряда: «Тревога · 4 мин назад», «Отлучка · вернётся через 8 мин»,
 * «Рядом · только что» для того, кого мой телефон слышит напрямую, иначе «Слышали 3 мин назад».
 */
fun memberStatusLine(member: MemberView): String {
    val age = formatAge(member.lastContact)
    return when {
        member.hasSos -> "SOS · $age"
        member.hasActiveAlarm -> "Тревога · $age"
        member.presence == Presence.LOST -> "Не слышно · $age"
        member.presence == Presence.AWAY -> {
            val remaining = member.awayRemainingMs
            if (remaining != null && remaining > 0) {
                "Отлучка · вернётся через ${(remaining + 59_999) / 60_000} мин"
            } else {
                "Отлучка · срок вышел"
            }
        }
        member.heardDirectlyNow -> "Рядом · $age"
        member.lastContact == null -> "Ещё не слышали"
        else -> "Слышали $age"
    }
}

/** Заряд, если он не выше [LOW_BATTERY_PERCENT]; иначе null, и в списке заряд не показывается. */
fun lowBattery(member: MemberView): Int? = member.batteryPercent?.takeIf { it <= LOW_BATTERY_PERCENT }

/**
 * Подпись своей карточки: роль и сколько остальных участников мой телефон слышит напрямую,
 * «Руководитель · слышу 3 из 4». Пока в группе никого, кроме меня, только роль.
 */
fun selfSubtitle(view: GroupView): String {
    val role = if (view.iAmLeader) "Руководитель" else "Участник"
    val others = view.members.filterNot { it.isMe }
    if (others.isEmpty()) return role
    return "$role · слышу ${others.count { it.heardDirectlyNow }} из ${others.size}"
}

/** «Ответил 1 из 4 · молчат Даниил, Василиса». Молчащие это те, кто рядом, но не ответил. */
fun rollcallSummary(rollcall: RollcallView, names: Map<MemberId, String>): String {
    val answered = rollcall.answered.size
    val total = answered + rollcall.silentNearby.size + rollcall.notHeard.size
    val verb = plural(answered, "Ответил", "Ответили", "Ответили")
    val silent = rollcall.silentNearby.mapNotNull { names[it] }
    return if (silent.isEmpty()) "$verb $answered из $total" else "$verb $answered из $total · молчат ${silent.joinToString(", ")}"
}

/** Куда идти к участнику словами: «на северо-запад». null для меня, без азимута и ближе 5 м. */
fun directionWords(member: MemberView): String? = placeOf(member)?.sector?.let { SIDES_TOWARDS[it] }

/**
 * Кто объявил тревоги о человеке: «объявили вы», «объявил(а) Ольга», «объявили Ольга, Даниил».
 * Род по имени не угадать, поэтому у одного автора окончание в скобках.
 */
fun raisedByText(alarms: List<AlarmView>, me: MemberId): String? {
    if (alarms.isEmpty()) return null
    if (alarms.size == 1) {
        val alarm = alarms.single()
        return if (alarm.raisedBy == me) "объявили вы" else "объявил(а) ${alarm.raisedByName}"
    }
    return "объявили " + alarms.distinctBy { it.raisedBy }.joinToString(", ") { if (it.raisedBy == me) "вы" else it.raisedByName }
}

/**
 * Что известно о том, кто ещё не ответил на перекличку. «Уведомление дошло», если его сводка уже подтвердила
 * перекличку ([received]). Иначе для неслышимых «Не слышно 4 мин», для остальных «Рядом, ответа пока нет».
 */
fun rollcallPendingLine(member: MemberView?, notHeard: Boolean, received: Boolean): String {
    if (!notHeard) return if (received) "Рядом, уведомление дошло" else "Рядом, ответа пока нет"
    val contact = member?.lastContact
    val silence = when {
        contact == null -> "Не слышно"
        contact.approximate -> "Не слышно примерно ${formatDuration(contact.ms)}"
        else -> "Не слышно ${formatDuration(contact.ms)}"
    }
    return if (received) "$silence, уведомление дошло" else silence
}
