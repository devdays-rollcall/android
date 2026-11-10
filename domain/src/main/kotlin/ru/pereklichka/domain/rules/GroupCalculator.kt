package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupSnapshot
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.time.Clock
import javax.inject.Inject

/**
 * Правила статусов: из снимка данных считает [GroupView] для экранов и `StatusMonitor`.
 *
 * Вызывает `GroupViewProvider` раз в 2 секунды и при изменении данных. Это чистая функция: никакого
 * состояния между вызовами и никаких полей «статус» в базе. Тревоги, перекличка и состояние маршрута
 * зависят только от множества событий: одинаковые события в любом порядке и с повторами дают одинаковый
 * результат на всех телефонах. Настенное время в расчётах не участвует.
 */
class GroupCalculator @Inject constructor(private val thresholds: Thresholds) {

    /**
     * @param heardAgo кого мой телефон слышал напрямую и сколько миллисекунд назад, из `PresenceTracker.heardAgo()`.
     * @param myFix моя последняя фиксация для расстояний и направлений; null, если её ещё нет.
     */
    fun compute(snapshot: GroupSnapshot, heardAgo: Map<MemberId, Long>, myFix: Fix?, clock: Clock): GroupView {
        // TODO(Никита): посчитать GroupView по правилам: состав, маршрут, присутствие, тревоги, перекличка, подсказки.
        //  Событие UNKNOWN пропускать; последний контакт по формуле из 03, раздел «Кого телефон слышит»;
        //  «слышу напрямую» значит heardAgo не больше PresenceTracker.HEARD_NOW_MS.
        //  docs/team/techlead.md, тесты в GroupCalculatorTest
        throw NotImplementedError(
            "TODO(Никита): посчитать GroupView по правилам: состав, маршрут, присутствие, тревоги, перекличка, подсказки.",
        )
    }
}
