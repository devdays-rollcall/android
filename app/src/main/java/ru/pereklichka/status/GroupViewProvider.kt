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
 * Единственный источник [GroupView] для экранов и `StatusMonitor`, поэтому экран и уведомления не расходятся.
 *
 * Пересчитывает вид через [GroupCalculator] при каждом новом снимке хранилища, смене своей позиции или режима демо
 * и раз в [REFRESH_MS]: «Не слышно» наступает со временем, даже если в базе ничего не изменилось.
 * Пока на [view] никто не подписан, пересчёт стоит, а значение сбрасывается в null: устаревший вид после паузы
 * дал бы `StatusMonitor` ложные уведомления. null значит, что группы нет или первый расчёт ещё не готов.
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
        // snapshot() вызывается только при подписке, а не при создании провайдера.
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

        /** Переживает поворот экрана без лишнего пересчёта с нуля. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
