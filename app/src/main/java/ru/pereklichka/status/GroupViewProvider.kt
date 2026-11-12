package ru.pereklichka.status

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.rules.GroupCalculator
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.rules.Thresholds
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.service.OwnLocation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Считает [GroupView]. Экраны, уведомления и служба берут вид только отсюда, поэтому экран и уведомления
 * не расходятся.
 *
 * Вид пересчитывается через [GroupCalculator] при каждом новом снимке хранилища, новой своей позиции
 * ([OwnLocation]) или смене режима демо ([AppSettings]) и ещё раз в [REFRESH_MS], потому что «Не слышно»
 * наступает со временем без всяких изменений в базе. Кого слышно напрямую, читается из [PresenceTracker]
 * в момент пересчёта, так что новый сосед появляется в виде с задержкой до 2 с.
 *
 * Подписаны `GroupViewModel`, [StatusMonitor] и `RouteService`. `GroupActions` берёт текущее значение,
 * а `UploadWorker`, `BootReceiver` и `RouteService` при перезапуске без экрана спрашивают маршрут через [awaitRoute],
 * `SosWorker` спрашивает, не сняли ли SOS, через [awaitAlarmActive].
 *
 * Пока на [view] никто не подписан, пересчёт стоит, а значение сбрасывается в null: устаревший вид после паузы
 * дал бы [StatusMonitor] ложные уведомления. null значит, что группы нет или первый расчёт ещё не готов.
 * Когда служба закрывает активную группу, вид тоже становится null: снимок хранилища без активной группы молчит,
 * и без отдельной подписки на группу экраны держали бы вид закрытой группы.
 */
@Singleton
class GroupViewProvider @Inject constructor(
    store: MeshStore,
    groups: GroupRepository,
    presence: PresenceTracker,
    ownLocation: OwnLocation,
    settings: AppSettings,
    clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val view: StateFlow<GroupView?> = groups.activeGroup.distinctUntilChanged().flatMapLatest { group ->
        if (group == null) {
            flowOf(null)
        } else {
            combine(
                store.snapshot().filter { it.group == group },
                settings.demoMode.map { demo -> GroupCalculator(if (demo) Thresholds.DEMO else Thresholds()) },
                ownLocation.lastFix,
                refreshTicks(),
            ) { snapshot, calculator, myFix, _ ->
                calculator.compute(snapshot, presence.heardAgo(), myFix, clock)
            }
        }
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        initialValue = null,
    )

    /**
     * Состояние маршрута по первому готовому расчёту. null, если за [FIRST_VIEW_TIMEOUT_MS] вид не появился:
     * группы нет или база не ответила. Нужен запуску без экрана, где на [view] ещё никто не подписан. Служба
     * и `BootReceiver` при null считают маршрут незавершённым, потому что лишний обмен безопаснее пропавшего,
     * а `UploadWorker` при null heartbeat не шлёт.
     */
    suspend fun awaitRoute(): RouteState? = withTimeoutOrNull(FIRST_VIEW_TIMEOUT_MS) { view.filterNotNull().first().route }

    /**
     * Активна ли сейчас тревога или SOS [id] по первому готовому расчёту. null, если вид не появился за
     * [FIRST_VIEW_TIMEOUT_MS]. Нужен `SosWorker`: снятый SOS на сервер не отправляется.
     */
    suspend fun awaitAlarmActive(id: EventId): Boolean? =
        withTimeoutOrNull(FIRST_VIEW_TIMEOUT_MS) { view.filterNotNull().first().alarms.any { it.id == id } }

    private fun refreshTicks() = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L

        /** Сколько миллисекунд держать расчёт без подписчиков, чтобы поворот экрана не запускал его с нуля. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** Первый расчёт после старта процесса читает базу, на это хватает доли секунды. */
        const val FIRST_VIEW_TIMEOUT_MS = 5_000L
    }
}
