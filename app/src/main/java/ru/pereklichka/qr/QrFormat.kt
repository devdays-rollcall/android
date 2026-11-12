package ru.pereklichka.qr

import ru.pereklichka.data.GroupToken
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Собирает и разбирает строку QR для вступления в группу: `pereklichka://join?v=1&t=<токен>&g=<название>&l=<id руководителя>`.
 * Название кодируется как в URL, поэтому в нём можно писать пробелы, `&` и кириллицу.
 *
 * Строку показывает экран сбора (`LobbyScreen`), разбирает экран вступления (`JoinScreen`) после сканирования.
 */
object QrFormat {

    private const val PREFIX = "pereklichka://join?"
    private const val VERSION = "1"

    /** Строка QR для [payload]. */
    fun encode(payload: QrPayload): String =
        PREFIX + "v=$VERSION&t=${payload.token}&g=${encodePart(payload.groupName)}&l=${payload.leaderId}"

    /** Разбирает отсканированный текст. Чужой или битый код даёт [QrParseResult.Invalid] с текстом для человека. */
    fun parse(text: String): QrParseResult {
        if (!text.startsWith(PREFIX)) return QrParseResult.Invalid("Это не QR-код Переклички")
        val query = text.removePrefix(PREFIX)
            .split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            .toMap()
        if (query["v"] != VERSION) return QrParseResult.Invalid("Код создан другой версией приложения")

        val token = query["t"].orEmpty()
        val groupName = decodePart(query["g"].orEmpty())?.trim()
        val leaderId = query["l"]?.let(::normalizedUuid)
        return when {
            !GroupToken.isWellFormed(token) || groupName == null || leaderId == null ->
                QrParseResult.Invalid("Код повреждён или считан не полностью")
            groupName.isEmpty() -> QrParseResult.Invalid("В коде нет названия группы")
            else -> QrParseResult.Valid(QrPayload(token, groupName, MemberId(leaderId)))
        }
    }

    // Строковое имя кодировки вместо Charset: перегрузка с Charset появилась только в Android 13.
    private fun encodePart(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decodePart(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /** UUID в нижнем регистре, как его пишет `MemberId.random()`, или null, если это не UUID. */
    private fun normalizedUuid(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it == value.lowercase() }
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Итог разбора QR. */
sealed interface QrParseResult {
    data class Valid(val payload: QrPayload) : QrParseResult

    /** [reason] показывается человеку как есть. */
    data class Invalid(val reason: String) : QrParseResult
}
