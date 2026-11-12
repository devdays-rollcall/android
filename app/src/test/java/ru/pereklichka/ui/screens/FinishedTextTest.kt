package ru.pereklichka.ui.screens

import ru.pereklichka.service.Exchange
import kotlin.test.Test
import kotlin.test.assertEquals

/** Подпись после завершения маршрута по состоянию обмена, которое публикует служба. */
class FinishedTextTest {

    @Test
    fun `пока обмен идёт, видно, сколько минут он ещё может продлиться`() {
        assertEquals(
            "Маршрут завершён. Приложение передаёт это остальным, пока все не получат, но не дольше 12 мин.",
            finishedText(Exchange.On(stopsAtElapsedMs = 0), minutesLeft = 12, hasOthers = true),
        )
        assertEquals(
            "Маршрут завершён. Приложение передаёт это остальным.",
            finishedText(Exchange.On(), minutesLeft = null, hasOthers = true),
        )
    }

    @Test
    fun `выключенный обмен не обещает передачу`() {
        assertEquals(
            "Маршрут завершён, все участники об этом знают. Обмен с группой выключен.",
            finishedText(Exchange.Off(finishConfirmedByAll = true), minutesLeft = null, hasOthers = true),
        )
        // Без других участников подтверждать было некому, и «все получили» звучало бы странно.
        assertEquals(
            "Маршрут завершён, обмен с группой выключен.",
            finishedText(Exchange.Off(finishConfirmedByAll = true), minutesLeft = null, hasOthers = false),
        )
        assertEquals("Маршрут завершён, обмен с группой выключен.", finishedText(Exchange.Off(), null, hasOthers = true))
    }

    @Test
    fun `отменённый сбор называется сбором`() {
        assertEquals(
            "Сбор отменён, все участники об этом знают. Обмен с группой выключен.",
            finishedText(Exchange.Off(finishConfirmedByAll = true), null, hasOthers = true, cancelled = true),
        )
    }
}
