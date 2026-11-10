package ru.pereklichka.domain.sync

/**
 * Состояние движка для экрана диагностики. Публикует его [SyncEngine.diagnostics].
 *
 * [running] ведёт сам движок, счётчики пополняют обработчики сообщений.
 */
data class SyncDiagnostics(
    val running: Boolean = false,
    val sentMessages: Int = 0,
    /** `PeerTransport.send` вернул false. */
    val failedSends: Int = 0,
    val receivedMessages: Int = 0,
    /** Не разобрались в [MeshCodec.decode]. */
    val corruptMessages: Int = 0,
    /** Сумма [ApplyResult] по всем применённым Delta: принято, повторы, не по порядку, конфликты. */
    val applied: ApplyResult = ApplyResult(),
)
