package ru.pereklichka.status

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.pereklichka.data.AppSettings
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.rules.GroupCalculator
import ru.pereklichka.domain.rules.GroupView
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
 * а `UploadWorker` будет ждать первый вид через `first()`.
 *
 * Пока на [view] никто не подписан, пересчёт стоит, а значение сбрасывается в null: устаревший вид после паузы
 * дал бы [StatusMonitor] ложные уведомления. null значит, что группы нет или первый расчёт ещё не готов.
 */
@Singleton
class GroupViewProvider @Inject constructor(
    store: MeshStore,
    presence: PresenceTracker,
    ownLocation: OwnLocation,
    settings: AppSettings,
    clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) {
    val view: StateFlow<GroupView?> = combine(
        // snapshot() вызывается при первой подписке. Так провайдер можно создать, не трогая хранилище,
        // и в фейковом режиме заглушка RoomMeshStore.snapshot() не падает.
        flow { emitAll(store.snapshot()) },
        settings.demoMode.map { demo -> GroupCalculator(if (demo) Thresholds.DEMO else Thresholds()) },
        ownLocation.lastFix,
        refreshTicks(),
    ) { snapshot, calculator, myFix, _ ->
        calculator.compute(snapshot, presence.heardAgo(), myFix, clock)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        initialValue = null,
    )

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
    }
}
