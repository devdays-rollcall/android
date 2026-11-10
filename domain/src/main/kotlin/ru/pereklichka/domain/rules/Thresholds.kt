package ru.pereklichka.domain.rules

/**
 * Пороги правил для [GroupCalculator] из `docs/03-sync-protocol.md`, раздел «Тайминги». `GroupViewProvider`
 * выбирает между значениями по умолчанию и [DEMO] по режиму демо из `AppSettings`.
 *
 * Порог «Не слышно» в режиме демо 60 с вместо 120 с, остальное от режима не зависит.
 */
data class Thresholds(
    /** Если последний контакт старше стольких миллисекунд, участник «Не слышно». */
    val lostAfterMs: Long = 120_000,
    /** Окно переклички в миллисекундах по возрасту события ROLLCALL. */
    val rollcallWindowMs: Long = 3 * 60_000,
) {
    companion object {
        val DEMO = Thresholds(lostAfterMs = 60_000)
    }
}
