package ru.pereklichka.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Токен группы и то, что из него выводится.
 *
 * Токен это 32 случайных байта в base64url без выравнивания, 43 символа. Им подписаны запросы к серверу,
 * он лежит в QR. Тег группы открыто уходит в эфир в serviceId Nearby, поэтому он вычисляется из токена
 * через HMAC и сам токен не раскрывает.
 */
object GroupToken {

    private const val TOKEN_BYTES = 32
    private const val TAG_BYTES = 8
    private const val TAG_MESSAGE = "pereklichka-mesh"

    /** Длина токена в символах. */
    const val LENGTH = 43

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Похоже ли значение на токен: 43 символа base64url, которые раскодируются в 32 байта. */
    fun isWellFormed(token: String): Boolean = token.length == LENGTH && decodeOrNull(token)?.size == TOKEN_BYTES

    /**
     * Тег группы: первые 8 байт HMAC-SHA256 строки `pereklichka-mesh` с ключом из байтов токена, в hex.
     * @throws IllegalArgumentException если [token] не похож на токен.
     */
    fun tagOf(token: String): String {
        val key = requireNotNull(decodeOrNull(token)) { "Не токен группы" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val digest = mac.doFinal(TAG_MESSAGE.encodeToByteArray())
        return digest.take(TAG_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** serviceId Nearby: телефоны с разными тегами друг друга не находят. */
    fun serviceIdOf(tag: String): String = "ru.pereklichka.g.$tag"

    private fun decodeOrNull(token: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(token)
    } catch (e: IllegalArgumentException) {
        null
    }
}
