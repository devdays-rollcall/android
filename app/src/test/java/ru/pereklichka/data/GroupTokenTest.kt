package ru.pereklichka.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Токен группы и тег из него. Эталонный тег закреплён, потому что телефоны с разным тегом не найдут друг друга. */
class GroupTokenTest {

    @Test
    fun `токен это 43 символа base64url`() {
        val token = GroupToken.generate()

        assertEquals(GroupToken.LENGTH, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(GroupToken.isWellFormed(token))
        assertNotEquals(token, GroupToken.generate())
    }

    @Test
    fun `обрезанный и чужой токен не похожи на токен`() {
        val token = GroupToken.generate()

        assertFalse(GroupToken.isWellFormed(token.dropLast(1)))
        assertFalse(GroupToken.isWellFormed(token.dropLast(1) + "="))
        assertFalse(GroupToken.isWellFormed("+".repeat(GroupToken.LENGTH)))
    }

    @Test
    fun `тег это 16 hex-символов, одинаковых для одного токена`() {
        val token = GroupToken.generate()

        val tag = GroupToken.tagOf(token)

        assertEquals(16, tag.length)
        assertTrue(tag.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(tag, GroupToken.tagOf(token))
        assertNotEquals(tag, GroupToken.tagOf(GroupToken.generate()))
        assertFalse(tag in token)
        assertEquals("ru.pereklichka.g.$tag", GroupToken.serviceIdOf(tag))
    }

    /**
     * Эталон посчитан отдельно на Python (hmac, hashlib): HMAC-SHA256 строки pereklichka-mesh с ключом из 32 нулевых
     * байтов, первые 8 байтов. Если значение изменилось, телефоны разных сборок перестанут находить друг друга.
     */
    @Test
    fun `тег токена из нулей не меняется между версиями`() {
        assertEquals("2390d604a0ba9160", GroupToken.tagOf("A".repeat(GroupToken.LENGTH)))
    }
}
