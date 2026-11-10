package ru.pereklichka.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.track.LocationSanitizer
import ru.pereklichka.domain.track.TrackThinner
import javax.inject.Inject

/**
 * GPS по состоянию маршрута. В «Сборе» GPS уже работает, чтобы приёмник прогрелся, но позиция идёт только
 * в [OwnLocation]. На маршруте фиксации, которые пропустил [TrackThinner], пишутся точками трека.
 * После завершения GPS выключается. Запускает `RouteService` на время работы службы.
 */
class TrackRecorder @Inject constructor(
    private val locationSource: LocationSource,
    private val sanitizer: LocationSanitizer,
    private val thinner: TrackThinner,
    private val store: MeshStore,
    private val ownLocation: OwnLocation,
    private val battery: BatteryLevel,
) {
    /** Работает, пока не отменят корутину. */
    suspend fun run(route: StateFlow<RouteState>) {
        route
            .map { it != RouteState.FINISHED }
            .distinctUntilChanged()
            .flatMapLatest { gpsOn -> if (gpsOn) locationSource.fixes else emptyFlow() }
            .collect { raw ->
                val fix = sanitizer.sanitize(raw) ?: return@collect
                ownLocation.update(fix)
                if (route.value == RouteState.ACTIVE && thinner.shouldKeep(fix)) {
                    store.appendOwnLocation(fix, battery.percent())
                }
            }
    }
}
