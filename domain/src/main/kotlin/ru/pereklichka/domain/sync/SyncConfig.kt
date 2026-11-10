package ru.pereklichka.domain.sync

/**
 * Тайминги и лимиты обмена из `03-sync-protocol.md`, раздел «Тайминги».
 *
 * На телефоне экземпляр создаёт модуль Hilt, тест может подставить свои значения.
 */
data class SyncConfig(
    /** Hello каждому соседу. */
    val helloIntervalMs: Long = 10_000,
    /** Разорвать соединение, если от соседа столько времени не было ни одного сообщения. */
    val silentDisconnectMs: Long = 45_000,
    /** Предел одного сообщения после сжатия; больше него Delta делится пополам. */
    val maxMessageBytes: Int = 32 * 1024,
    /** Событий в одной Delta. */
    val maxEventsPerDelta: Int = 200,
    /** Точек в одной Delta (одна порция). */
    val maxPointsPerDelta: Int = 300,
    /** Столько неразборчивых сообщений подряд от одного соседа рвут соединение. */
    val maxCorruptInRow: Int = 3,
    /** Своё состояние, которое пишет `SelfStateProducer`. */
    val stateIntervalMs: Long = 15_000,
    /** Сколько служба продолжает обмен после ROUTE_FINISHED, чтобы завершение дошло до всех. */
    val exchangeAfterFinishMs: Long = 15 * 60_000,
)
