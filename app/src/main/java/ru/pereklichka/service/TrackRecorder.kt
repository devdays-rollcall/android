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
 * Включает и выключает GPS по состоянию маршрута и пишет трек. Запускает его [RouteService] на время работы службы.
 *
 * В «Сборе» GPS уже работает, чтобы приёмник прогрелся, но позиция идёт только в [OwnLocation]. На маршруте
 * (ACTIVE) фиксация проходит [LocationSanitizer] и [TrackThinner], и прошедшие пишутся точками трека через
 * `MeshStore.appendOwnLocation`. После завершения GPS выключается.
 */
class TrackRecorder @Inject constructor(
    private val locationSource: LocationSource,
    private val sanitizer: LocationSanitizer,
    private val thinner: TrackThinner,
    private val store: MeshStore,
    private val ownLocation: OwnLocation,
    private val battery: BatteryLevel,
) {
    /** Работает, пока не отменят корутину. [route] даёт `RouteSession` из текущего `GroupView`. */
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
