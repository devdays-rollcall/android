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
 * Каждую фиксацию чистит [LocationSanitizer] и кладёт в [OwnLocation]. В «Сборе» на этом всё: GPS работает,
 * чтобы приёмник прогрелся. На маршруте (ACTIVE) фиксация ещё проходит [TrackThinner], и прошедшие пишутся
 * точками трека через `MeshStore.appendOwnLocation`. После завершения GPS выключается.
 */
class TrackRecorder @Inject constructor(
    private val locationSource: LocationSource,
    private val sanitizer: LocationSanitizer,
    private val thinner: TrackThinner,
    private val store: MeshStore,
    private val ownLocation: OwnLocation,
    private val battery: BatteryLevel,
) {
    /** Работает до отмены корутины. [route] даёт текущее состояние маршрута. */
    suspend fun run(route: StateFlow<RouteState>, onPoint: () -> Unit = {}) {
        route
            .map { it != RouteState.FINISHED }
            .distinctUntilChanged()
            .flatMapLatest { gpsOn -> if (gpsOn) locationSource.fixes else emptyFlow() }
            .collect { raw ->
                val fix = sanitizer.sanitize(raw) ?: return@collect
                ownLocation.update(fix)
                if (route.value == RouteState.ACTIVE && thinner.shouldKeep(fix)) {
                    store.appendOwnLocation(fix, battery.percent())
                    onPoint()
                }
            }
    }
}
