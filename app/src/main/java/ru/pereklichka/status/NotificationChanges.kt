package ru.pereklichka.status

import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.notify.NotifyEvent

/** Что показать ([show]) и какие уведомления убрать по ключу ([dismiss]) после перехода от одного [GroupView] к следующему. */
data class NotificationChanges(val show: List<NotifyEvent>, val dismiss: List<String>)

/**
 * Решает, какие уведомления дать при переходе от [previous] к [current]. Вызывает [StatusMonitor], проверяет
 * `NotificationChangesTest`. Побочных эффектов у функции нет.
 *
 * Новая тревога или SOS даёт AlarmRaised, пропавшая AlarmResolved. «Не слышно» (MemberLost) получает только
 * руководитель и только на маршруте, потому что в «Сборе» люди ещё не вышли, а после завершения уже разошлись.
 * Об участниках, которых не было слышно уже в момент ROUTE_STARTED, руководитель тоже узнаёт, а после
 * ROUTE_FINISHED все уведомления «Не слышно» убираются. Новая перекличка, на которую я не ответил, даёт
 * RollcallStarted, новая подсказка о близости NearbyConfirm. Когда до конца моей отлучки остаётся не больше
 * [AWAY_REMINDER_MS], приходит AwayEnding. Когда повод пропал, уведомление убирается: отлучку отменили, продлили
 * или она кончилась.
 * В начале каждого запуска монитора [previous] равен null, и об активных тревогах напомнят ещё раз.
 */
fun notificationChanges(previous: GroupView?, current: GroupView): NotificationChanges {
    val show = mutableListOf<NotifyEvent>()
    val dismiss = mutableListOf<String>()

    val previousAlarmIds = previous?.alarms.orEmpty().map { it.id }.toSet()
    val currentAlarmIds = current.alarms.map { it.id }.toSet()
    for (alarm in current.alarms) {
        if (alarm.id in previousAlarmIds) continue
        show += NotifyEvent.AlarmRaised(alarm.id, alarm.target, alarm.targetName, alarm.isSos, isAboutMe = alarm.target == current.me)
    }
    for (alarmId in previousAlarmIds - currentAlarmIds) show += NotifyEvent.AlarmResolved(alarmId)

    val wasLost = previous?.lostMembersToReport().orEmpty()
    val isLost = current.lostMembersToReport()
    for (member in current.members) {
        if (member.id in isLost && member.id !in wasLost) show += NotifyEvent.MemberLost(member.id, member.name)
    }
    for (member in wasLost - isLost) dismiss += NotifyEvent.lostKey(member)

    val previousRollcall = previous?.rollcall?.takeIf { previous.awaitsMyAnswer() }
    val currentRollcall = current.rollcall?.takeIf { current.awaitsMyAnswer() }
    if (currentRollcall != null && currentRollcall.id != previousRollcall?.id) {
        show += NotifyEvent.RollcallStarted(currentRollcall.id)
    }
    if (previousRollcall != null && previousRollcall.id != currentRollcall?.id) {
        dismiss += NotifyEvent.rollcallKey(previousRollcall.id)
    }

    val previousPrompts = previous?.prompts.orEmpty().map { it.member }.toSet()
    val currentPrompts = current.prompts.map { it.member }.toSet()
    for (prompt in current.prompts) {
        if (prompt.member !in previousPrompts) show += NotifyEvent.NearbyConfirm(prompt.member, prompt.name)
    }
    for (member in previousPrompts - currentPrompts) dismiss += NotifyEvent.nearbyKey(member)

    val wasAwayEnding = previous?.myAwayEnding() == true
    val isAwayEnding = current.myAwayEnding()
    if (isAwayEnding && !wasAwayEnding) show += NotifyEvent.AwayEnding
    if (wasAwayEnding && !isAwayEnding) dismiss += NotifyEvent.AWAY_ENDING_KEY

    return NotificationChanges(show, dismiss)
}

/** За сколько миллисекунд до конца моей отлучки спросить «Вернулись?». */
const val AWAY_REMINDER_MS = 60_000L

private fun GroupView.myAwayEnding(): Boolean {
    val remainingMs = members.firstOrNull { it.isMe }?.awayRemainingMs ?: return false
    return remainingMs <= AWAY_REMINDER_MS
}

private fun GroupView.lostMembersToReport(): Set<MemberId> =
    if (iAmLeader && route == RouteState.ACTIVE) members.filter { it.presence == Presence.LOST }.map { it.id }.toSet() else emptySet()

/** Идёт перекличка, на которую мне нужно ответить. Руководитель её сам объявил и не отвечает. */
private fun GroupView.awaitsMyAnswer(): Boolean {
    val rollcall = rollcall ?: return false
    return !iAmLeader && me !in rollcall.answered
}
