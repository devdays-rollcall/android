package ru.pereklichka.status

import kotlinx.coroutines.flow.filterNotNull
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.notify.Notifier
import javax.inject.Inject

/**
 * Превращает изменения [GroupView] в уведомления. Запускает его `RouteService` на время работы службы.
 * Правила переходов в [notificationChanges].
 */
class StatusMonitor @Inject constructor(
    private val viewProvider: GroupViewProvider,
    private val notifier: Notifier,
) {
    /** Работает, пока не отменят корутину. После перезапуска начинает с пустого прошлого. */
    suspend fun run() {
        var previous: GroupView? = null
        viewProvider.view.filterNotNull().collect { current ->
            val changes = notificationChanges(previous, current)
            changes.dismiss.forEach(notifier::dismiss)
            changes.show.forEach(notifier::show)
            previous = current
        }
    }
}
