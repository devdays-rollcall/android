package ru.pereklichka.domain.sync

/**
 * Тайминги и лимиты обмена из `docs/03-sync-protocol.md`, раздел «Тайминги».
 *
 * На телефоне экземпляр со значениями по умолчанию создаёт `AppModule`, тест может подставить свои.
 */
data class SyncConfig(
    /** Как часто слать сводку (Hello) каждому соседу, мс. */
    val helloIntervalMs: Long = 10_000,
    /** Через сколько миллисекунд без единого сообщения от соседа рвать соединение. */
    val silentDisconnectMs: Long = 45_000,
    /** Предел одного сообщения после сжатия, байт. Его выбрал протокол, у Nearby предел нагрузки BYTES больше, около 1 МБ. */
    val maxMessageBytes: Int = 32 * 1024,
    /** Сколько событий помещается в одну Delta. */
    val maxEventsPerDelta: Int = 200,
    /** Сколько точек помещается в одну Delta, это и есть одна порция точек. */
    val maxPointsPerDelta: Int = 300,
    /** Столько неразборчивых сообщений подряд от одного соседа рвут соединение. */
    val maxCorruptInRow: Int = 3,
    /** Как часто `SelfStateProducer` пишет своё состояние, мс. */
    val stateIntervalMs: Long = 15_000,
    /** Сколько миллисекунд служба продолжает обмен после ROUTE_FINISHED, чтобы завершение дошло до всех. */
    val exchangeAfterFinishMs: Long = 15 * 60_000,
)
