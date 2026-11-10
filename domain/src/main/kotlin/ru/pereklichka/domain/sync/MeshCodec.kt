package ru.pereklichka.domain.sync

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

/**
 * Превращает [MeshMessage] в байты для `PeerTransport.send` и обратно.
 *
 * Формат: один байт версии формата, затем JSON, сжатый gzip. JSON выбран ради отладки, его можно
 * распечатать в лог. Декодер игнорирует неизвестные поля, а незнакомый тип события превращает
 * в `EventType.UNKNOWN`, поэтому новое поле или тип в следующей версии не ломают обмен со старой.
 */
class MeshCodec @Inject constructor(private val config: SyncConfig) {

    /**
     * Кодирует сообщение. Возвращает `null`, если после сжатия оно больше [SyncConfig.maxMessageBytes]:
     * такую Delta движок делит пополам и кодирует половины.
     */
    fun encode(message: MeshMessage): ByteArray? {
        val json = meshJson.encodeToString(MeshMessage.serializer(), message).encodeToByteArray()
        val output = ByteArrayOutputStream()
        output.write(FORMAT_VERSION.toInt())
        GZIPOutputStream(output).use { it.write(json) }
        return output.toByteArray().takeIf { it.size <= config.maxMessageBytes }
    }

    /**
     * Разбирает сообщение соседа.
     *
     * @throws MeshFormatException если байты не разбираются: чужая версия формата, битый gzip или JSON,
     * нет обязательного поля. Движок отбрасывает такое сообщение и считает его в диагностике.
     */
    fun decode(bytes: ByteArray): MeshMessage {
        if (bytes.isEmpty()) throw MeshFormatException("Пустое сообщение")
        if (bytes[0] != FORMAT_VERSION) throw MeshFormatException("Неизвестная версия формата ${bytes[0]}")
        val json = try {
            GZIPInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use { it.readAtMost(MAX_JSON_BYTES) }
        } catch (e: IOException) {
            throw MeshFormatException("Не удалось распаковать gzip", e)
        }
        return try {
            meshJson.decodeFromString(MeshMessage.serializer(), json.decodeToString())
        } catch (e: IllegalArgumentException) {
            // SerializationException наследует IllegalArgumentException.
            throw MeshFormatException("Не удалось разобрать JSON", e)
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            output.write(buffer, 0, read)
            if (output.size() > limit) throw IOException("Распакованное сообщение больше $limit байт")
        }
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1

        /** Предел распакованного JSON с большим запасом: сжатый мусор может развернуться в гигабайты. */
        const val MAX_JSON_BYTES = 4 * 1024 * 1024

        val meshJson = Json {
            // Хранилище держит точки с NaN в префиксе автора (помечает их rejected), и пересылать их тоже нужно.
            allowSpecialFloatingPointValues = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

/**
 * Сообщение соседа не разбирается. Это ожидаемая ошибка: такое сообщение может прислать несовместимая
 * версия приложения или сосед с ошибкой в коде, и падать из-за него нельзя.
 */
class MeshFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
