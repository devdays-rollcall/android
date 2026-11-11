package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.time.Stamped

/** Записи из Delta соседа с уже поставленной отметкой свежести. Получается из [Delta.toIncoming], уходит в [MeshStore.apply]. */
data class IncomingDelta(
    val control: List<Stamped<ControlEvent>> = emptyList(),
    val states: List<Stamped<MemberState>> = emptyList(),
    val locations: List<LocationPoint> = emptyList(),
)

/**
 * Итог [MeshStore.apply]: сколько записей принято и почему остальные отброшены.
 *
 * По [newControl] движок решает, рассылать ли события остальным соседям, а суммы показывает диагностика.
 */
data class ApplyResult(
    val newControl: Int = 0,
    val newStates: Int = 0,
    val newLocations: Int = 0,
    /** Уже были: запись с тем же номером и тем же UUID уже лежит у меня или состояние не новее моего. */
    val duplicates: Int = 0,
    /** Не встают в префикс: номер больше следующего (такие придут снова по порядку) или меньше единицы. */
    val outOfOrder: Int = 0,
    /**
     * Тот же автор и номер с другим UUID, UUID уже занят другой записью, моё же событие или точка с номером больше
     * моего максимума или моё же состояние новее моего. Хранилище оставляет первую запись. В исправной группе
     * конфликтов не бывает, а ненулевой счётчик в диагностике значит, что кто-то пишет от чужого имени или в коде
     * ошибка.
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
