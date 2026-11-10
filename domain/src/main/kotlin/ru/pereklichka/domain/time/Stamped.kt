package ru.pereklichka.domain.time

/**
 * Запись с отметкой свежести: сколько ей было в момент получения и по каким часам этот момент засечён.
 *
 * Часы телефонов в походе расходятся, поэтому время создания записи, записанное другим телефоном,
 * сравнивать нельзя. Вместо него запись несёт свой возраст, а текущий возраст считает [ageNow].
 * Отметку ставят хранилище для своих записей ([own]) и `Delta.toIncoming` для пришедших от соседа ([received]).
 * Хранилище сохраняет все четыре поля как есть.
 */
data class Stamped<out T>(
    val value: T,
    val ageAtReceiptMs: Long,
    val receivedAtElapsedMs: Long,
    val receivedBootId: String,
    val receivedAtWallMs: Long,
) {
    companion object {
        /** Предел возраста из сети: всё старше суток считается возрастом в сутки. */
        const val MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L

        /**
         * Отметка для записи, которая пришла от соседа с возрастом [ageMs].
         *
         * Возраст из сети может быть повреждён, поэтому отрицательный становится нулём,
         * а больше суток ограничивается сутками (`docs/03-sync-protocol.md`, «Повреждённые сообщения»).
         */
        fun <T> received(value: T, ageMs: Long, clock: Clock): Stamped<T> = Stamped(
            value = value,
            ageAtReceiptMs = ageMs.coerceIn(0, MAX_AGE_MS),
            receivedAtElapsedMs = clock.elapsedMs(),
            receivedBootId = clock.bootId(),
            receivedAtWallMs = clock.wallMs(),
        )

        /** Отметка для только что созданной своей записи: возраст 0. */
        fun <T> own(value: T, clock: Clock): Stamped<T> = received(value, 0, clock)
    }
}

/**
 * Текущий возраст записи.
 *
 * Если телефон с момента получения не перезагружался, возраст точный и считается по монотонным часам.
 * После перезагрузки монотонные часы начались заново, поэтому берётся разница настенных часов,
 * и возраст помечается как примерный.
 */
fun Stamped<*>.ageNow(clock: Clock): Age {
    if (receivedBootId == clock.bootId()) {
        return Age(ageAtReceiptMs + (clock.elapsedMs() - receivedAtElapsedMs), approximate = false)
    }
    // Настенные часы могли перевести назад, а возраст уменьшаться не должен.
    val wallPassedMs = (clock.wallMs() - receivedAtWallMs).coerceAtLeast(0)
    return Age(ageAtReceiptMs + wallPassedMs, approximate = true)
}
