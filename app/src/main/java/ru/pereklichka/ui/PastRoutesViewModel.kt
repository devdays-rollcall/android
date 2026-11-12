package ru.pereklichka.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.PastRoute
import ru.pereklichka.data.RouteHistory
import ru.pereklichka.data.db.key
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.domain.rules.GroupCalculator
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Thresholds
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.ui.components.HistoryLine
import ru.pereklichka.ui.components.TrackStatus
import ru.pereklichka.ui.components.historyLines
import javax.inject.Inject

/** Прошлый маршрут в списке на стартовом экране и что с его треком. */
data class PastRouteItem(val route: PastRoute, val track: TrackStatus) {
    /** Ключ группы в базе, по нему открывается экран маршрута. */
    val key: String get() = route.group.key
}

/**
 * Экран прошлого маршрута: [item] из списка, участники (я первым, потом руководитель, остальные по имени)
 * и история событий.
 */
data class PastRouteDetails(
    val item: PastRouteItem,
    val members: List<MemberView>,
    val history: List<HistoryLine>,
)

/**
 * Прошлые маршруты для стартового экрана и экрана маршрута: список, подробности одного маршрута и удаление.
 * Записи читает [RouteHistory], удаляет [GroupRepository.deletePastGroup].
 */
@HiltViewModel
class PastRoutesViewModel @Inject constructor(
    private val history: RouteHistory,
    private val repository: GroupRepository,
    settings: AppSettings,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Прошлые маршруты, новые первыми; пустой список, пока база не ответила. */
    val routes: StateFlow<List<PastRouteItem>> = combine(history.pastRoutes, settings.serverUrl) { routes, serverUrl ->
        routes.map { PastRouteItem(it, TrackStatus(it.points, it.unsentPoints, hasServer = serverUrl.isNotBlank())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue = emptyList())

    /**
     * Подробности маршрута [key]; null, пока база не ответила или если маршрута больше нет. Присутствие и тревоги
     * прошлого маршрута не показываются, поэтому пороги демо здесь не нужны.
     */
    fun details(key: String): Flow<PastRouteDetails?> = combine(routes, history.snapshot(key)) { items, snapshot ->
        val item = items.firstOrNull { it.key == key }
        if (item == null || snapshot == null) return@combine null
        val view = GroupCalculator(Thresholds()).compute(snapshot, heardAgo = emptyMap(), myFix = null, clock = clock)
        PastRouteDetails(
            item = item,
            members = view.members.sortedWith(compareBy({ !it.isMe }, { !it.isLeader }, { it.name })),
            history = historyLines(snapshot.events, snapshot.group.leaderId, snapshot.group.me),
        )
    }

    /**
     * Удаляет прошлый маршрут [key] со всеми записями. Удаление идёт в области приложения: экран закрывается сразу,
     * и отмена его области откатила бы транзакцию.
     */
    fun delete(key: String) {
        appScope.launch { repository.deletePastGroup(key) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
