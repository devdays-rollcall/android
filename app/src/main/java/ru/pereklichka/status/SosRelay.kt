package ru.pereklichka.status

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import ru.pereklichka.data.AppSettings
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.rules.AlarmView
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.upload.UploadScheduler
import javax.inject.Inject

/**
 * Передаёт на сервер каждый SOS, который видит этот телефон, свой и чужой: сигнал уйдёт наблюдателям с того телефона
 * группы, который первым поймает интернет. Запускает его `RouteService` на время работы службы, рядом со
 * [StatusMonitor]. Новый SOS ставит отправку через [UploadScheduler.sendSos], снятый снимает ждущую отправку.
 *
 * Сервер не заводит второе предупреждение на тот же SOS, но уведомление в ntfy шлёт на каждый запрос, поэтому
 * наблюдатели могут получить его по разу от каждого телефона с интернетом.
 */
class SosRelay @Inject constructor(
    private val viewProvider: GroupViewProvider,
    private val uploadScheduler: UploadScheduler,
    private val settings: AppSettings,
) {
    /** Работает, пока не отменят корутину. Смотрит только на виды группы [group], как и [StatusMonitor]. */
    suspend fun run(group: GroupInfo) {
        var previous: GroupView? = null
        viewProvider.view.filterNotNull().filter { it.group == group }.collect { current ->
            val changes = sosRelayChanges(previous, current)
            val serverUrl = settings.serverUrl.value
            if (serverUrl.isNotBlank()) changes.send.forEach { uploadScheduler.sendSos(group, it.id, it.target, serverUrl) }
            changes.cancel.forEach { uploadScheduler.cancelSos(group.tag, it.id) }
            previous = current
        }
    }
}

/** Какие SOS поставить на отправку ([send]) и чьи ждущие отправки снять ([cancel]). */
data class SosRelayChanges(val send: List<AlarmView>, val cancel: List<AlarmView>)

/**
 * SOS, появившиеся и пропавшие при переходе от [previous] к [current]. При [previous] = null на отправку уходят все
 * активные SOS: после перезапуска службы повторная постановка ничего не меняет, задача уникальна по id SOS.
 * Проверяет `SosRelayTest`.
 */
fun sosRelayChanges(previous: GroupView?, current: GroupView): SosRelayChanges {
    val before = previous?.alarms.orEmpty().filter { it.isSos }
    val now = current.alarms.filter { it.isSos }
    val beforeIds = before.map { it.id }.toSet()
    val nowIds = now.map { it.id }.toSet()
    return SosRelayChanges(send = now.filter { it.id !in beforeIds }, cancel = before.filter { it.id !in nowIds })
}
