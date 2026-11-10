package ru.pereklichka.status

import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.notify.NotifyEvent

/** Что показать и что убрать после перехода от одного [GroupView] к следующему. */
data class NotificationChanges(val show: List<NotifyEvent>, val dismiss: List<String>)

/**
 * Переходы из `docs/team/techlead.md`, раздел «StatusMonitor». Чистая функция, её проверяет `NotificationChangesTest`.
 *
 * Новая тревога или SOS даёт AlarmRaised, пропавшая AlarmResolved. Переход участника в LOST даёт MemberLost только
 * руководителю на маршруте: в сборе люди ещё не вышли, после завершения уже разошлись. Новая перекличка, на которую
 * я не ответил, даёт RollcallStarted, новая подсказка о близости NearbyConfirm. Когда повод пропал, уведомление
 * убирается. [previous] равен null после перезапуска процесса, тогда об активных тревогах напомнят ещё раз.
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

    val wasLost = previous?.members.orEmpty().filter { it.presence == Presence.LOST }.map { it.id }.toSet()
    val isLost = current.members.filter { it.presence == Presence.LOST }.map { it.id }.toSet()
    if (current.iAmLeader && current.route == RouteState.ACTIVE) {
        for (member in current.members) {
            if (member.id in isLost && member.id !in wasLost) show += NotifyEvent.MemberLost(member.id, member.name)
        }
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

    return NotificationChanges(show, dismiss)
}

/** Идёт перекличка, на которую мне нужно ответить. Руководитель её сам объявил и не отвечает. */
private fun GroupView.awaitsMyAnswer(): Boolean {
    val rollcall = rollcall ?: return false
    return !iAmLeader && me !in rollcall.answered
}
