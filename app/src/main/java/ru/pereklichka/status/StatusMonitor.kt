package ru.pereklichka.status

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.notify.Notifier
import ru.pereklichka.notify.NotifyEvent
import javax.inject.Inject

/**
 * Превращает изменения [GroupView] в уведомления: новая тревога, «Не слышно», перекличка, подсказка о близости.
 *
 * Запускает его `RouteService` на время работы службы. Монитор сравнивает каждый новый вид из [GroupViewProvider]
 * с предыдущим по правилам [notificationChanges] и вызывает [Notifier]. Что показывать, решает он, а как
 * показывать, решает `AndroidNotifier`.
 */
class StatusMonitor @Inject constructor(
    private val viewProvider: GroupViewProvider,
    private val notifier: Notifier,
) {
    /**
     * Работает, пока не отменят корутину. Смотрит только на виды группы [group]: после смены группы старый вид
     * не должен дать уведомлений. После перезапуска начинает с пустого прошлого. При отмене убирает всё, что
     * успел показать, и тем останавливает звук тревоги, потому что снять эти уведомления потом будет некому.
     */
    suspend fun run(group: GroupInfo) {
        showNotifications(viewProvider.view.filterNotNull().filter { it.group == group }, notifier)
    }
}

/**
 * Тело [StatusMonitor.run] без провайдера вида. Проверяет `StatusMonitorTest`. Показанные ключи учитываются, чтобы
 * при отмене убрать их уведомления. `AlarmResolved` приходит через `show`, но уведомление своего ключа убирает,
 * поэтому ключ из учёта вычёркивается.
 */
internal suspend fun showNotifications(views: Flow<GroupView>, notifier: Notifier) {
    var previous: GroupView? = null
    val shown = mutableSetOf<String>()
    try {
        views.collect { current ->
            val changes = notificationChanges(previous, current)
            changes.dismiss.forEach { key ->
                notifier.dismiss(key)
                shown -= key
            }
            changes.show.forEach { event ->
                notifier.show(event)
                if (event is NotifyEvent.AlarmResolved) shown -= event.key else shown += event.key
            }
            previous = current
        }
    } finally {
        shown.forEach(notifier::dismiss)
    }
}
