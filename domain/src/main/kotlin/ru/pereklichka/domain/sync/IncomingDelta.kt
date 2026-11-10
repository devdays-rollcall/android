package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.time.Stamped

/** Записи от соседа с уже поставленной отметкой свежести. Получается из [Delta.toIncoming]. */
data class IncomingDelta(
    val control: List<Stamped<ControlEvent>> = emptyList(),
    val states: List<Stamped<MemberState>> = emptyList(),
    val locations: List<LocationPoint> = emptyList(),
)

/**
 * Итог [MeshStore.apply]: сколько записей принято и почему остальные отброшены.
 *
 * Движок по [newControl] решает, рассылать ли события остальным соседям, а суммы показывает диагностика.
 */
data class ApplyResult(
    val newControl: Int = 0,
    val newStates: Int = 0,
    val newLocations: Int = 0,
    /** Уже были: номер не больше моего максимума по автору или состояние не новее того, что у меня. */
    val duplicates: Int = 0,
    /** Не встают в префикс: номер больше следующего или меньше единицы. Придут снова в правильном порядке. */
    val outOfOrder: Int = 0,
    /**
     * Тот же автор и номер, но другой UUID, UUID уже занят другой записью или запись от моего имени новее моих.
     * Оставлена первая.
     * В исправной группе их не бывает, поэтому каждый случай стоит разобрать.
     */
    val conflicts: Int = 0,
) {
    operator fun plus(other: ApplyResult): ApplyResult = ApplyResult(
        newControl = newControl + other.newControl,
        newStates = newStates + other.newStates,
        newLocations = newLocations + other.newLocations,
        duplicates = duplicates + other.duplicates,
        outOfOrder = outOfOrder + other.outOfOrder,
        conflicts = conflicts + other.conflicts,
    )
}
