package ru.pereklichka.domain.model

import ru.pereklichka.domain.time.Stamped

/**
 * Всё, из чего `GroupCalculator` считает статусы: группа, события всех авторов и последние состояния.
 *
 * Отдаёт его `MeshStore.snapshot()`. Точек трека здесь нет: их тысячи, а позицию для экранов
 * даёт состояние участника. Порядок [events] ничего не значит, правила от него не зависят.
 */
data class GroupSnapshot(
    val group: GroupInfo,
    val events: List<Stamped<ControlEvent>>,
    val states: Map<MemberId, Stamped<MemberState>>,
)
