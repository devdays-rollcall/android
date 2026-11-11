package ru.pereklichka.domain.testing

import ru.pereklichka.domain.time.Clock

/**
 * Часы для тестов. Время берётся из [now], обычно это виртуальное время теста
 * (`{ testScheduler.currentTime }`) или переменная, которую тест двигает сам.
 *
 * Монотонные часы отсчитываются от последней «загрузки» и обнуляются в [reboot], как `elapsedRealtime`.
 * Настенные часы можно сдвинуть через [wallShiftMs], чтобы проверить, что расчёты от них не зависят.
 */
class FakeClock(private val now: () -> Long) : Clock {

    private var bootNumber = 1
    private var bootStartedAt = now()

    /** Насколько настенные часы этого телефона расходятся с истинным временем. */
    var wallShiftMs: Long = 0

    override fun elapsedMs(): Long = now() - bootStartedAt

    override fun wallMs(): Long = WALL_EPOCH_MS + now() + wallShiftMs

    override fun bootId(): String = bootNumber.toString()

    /** Перезагрузка телефона: монотонные часы начинаются с нуля, номер загрузки растёт. */
    fun reboot() {
        bootNumber++
        bootStartedAt = now()
    }

    private companion object {
        /** 10 ноября 2026 года, чтобы настенное время в логах тестов было похоже на настоящее. */
        const val WALL_EPOCH_MS = 1_794_268_800_000L
    }
}
