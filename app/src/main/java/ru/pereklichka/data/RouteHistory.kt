package ru.pereklichka.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.PastRouteRow
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.GroupSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Прошлый маршрут для списка на стартовом экране. Время начала и конца по часам этого телефона, null, если
 * руководитель не нажал «Выходим» или не завершил маршрут. [members] считает всех вступивших, и вышедших тоже.
 */
data class PastRoute(
    val group: GroupInfo,
    val startedAtWallMs: Long?,
    val finishedAtWallMs: Long?,
    val members: Int,
    val points: Int,
    val unsentPoints: Int,
    val iLeft: Boolean,
)

/**
 * Читает прошлые маршруты, то есть группы, которые уже не активны, но лежат на телефоне со всеми записями.
 * Удаляет их `GroupRepository.deletePastGroup`.
 */
@Singleton
class RouteHistory @Inject constructor(private val db: AppDatabase) {

    /** Прошлые маршруты, новые первыми; новое значение после каждого изменения их записей. */
    val pastRoutes: Flow<List<PastRoute>> = db.groupDao().observePast().map { rows -> rows.map { it.toPastRoute() } }

    /** Группа с ключом [key] (`GroupInfo.key`), её события и состояния; null, если такой группы нет или её удалили. */
    fun snapshot(key: String): Flow<GroupSnapshot?> = db.groupDao().observe(key).flatMapLatest { entity ->
        if (entity == null) {
            flowOf(null)
        } else {
            combine(db.controlEventDao().observeAll(key), db.memberStateDao().observeAll(key)) { events, states ->
                GroupSnapshot(
                    group = entity.toModel(),
                    events = events.map { it.toModel() },
                    states = states.map { it.toModel() }.associateBy { it.value.member },
                )
            }
        }
    }

    private fun PastRouteRow.toPastRoute() = PastRoute(
        group = group.toModel(),
        startedAtWallMs = startedAtWallMs,
        finishedAtWallMs = finishedAtWallMs,
        members = members,
        points = points,
        unsentPoints = unsentPoints,
        iLeft = iLeft,
    )
}
