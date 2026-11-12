package ru.pereklichka.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Работает ли обмен с группой, по словам самой [RouteService]. */
sealed interface Exchange {
    /**
     * Служба не работает: её не запускали в этом процессе, её выключили или она выключилась сама после завершения
     * маршрута. [finishConfirmedByAll] true, если служба выключилась раньше срока, потому что завершение
     * подтвердили все участники.
     */
    data class Off(val finishConfirmedByAll: Boolean = false) : Exchange

    /**
     * Служба работает. После завершения маршрута [stopsAtElapsedMs] задан: служба выключится сама не позже этого
     * момента по `Clock.elapsedMs`, а раньше, если завершение подтвердят все участники.
     */
    data class On(val stopsAtElapsedMs: Long? = null) : Exchange
}

/**
 * Состояние обмена для экранов. Пишет его только [RouteService], поэтому экран после её самостоятельной остановки
 * или перезапуска процесса не показывает обмен, которого нет. Новый процесс начинает с [Exchange.Off], и это
 * правда: служба, которую система вернула после убийства процесса, сама объявит [Exchange.On].
 */
@Singleton
class ExchangeStatus @Inject constructor() {
    private val state = MutableStateFlow<Exchange>(Exchange.Off())

    val exchange: StateFlow<Exchange> = state.asStateFlow()

    internal fun set(exchange: Exchange) {
        state.value = exchange
    }
}
