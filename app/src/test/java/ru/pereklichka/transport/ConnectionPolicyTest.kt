package ru.pereklichka.transport

import ru.pereklichka.domain.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Политика соединений из `docs/03-sync-protocol.md`: резерв четвёртого слота, кто запрашивает первым, паузы повторов. */
class ConnectionPolicyTest {

    private val a = MemberId("1a")
    private val b = MemberId("2b")
    private val c = MemberId("3c")
    private val d = MemberId("4d")
    private val e = MemberId("5e")

    @Test
    fun `до трёх соединений подключаться можно к любому`() {
        assertTrue(ConnectionPolicy.mayConnect(d, connected = emptySet(), blocked = emptySet(), isOutsider = false))
        assertTrue(ConnectionPolicy.mayConnect(d, connected = setOf(a, b), blocked = emptySet(), isOutsider = false))
    }

    @Test
    fun `четвёртое соединение только для телефона из другой части сети`() {
        val three = setOf(a, b, c)

        assertFalse(ConnectionPolicy.mayConnect(d, three, blocked = emptySet(), isOutsider = false))
        assertTrue(ConnectionPolicy.mayConnect(d, three, blocked = emptySet(), isOutsider = true))
    }

    @Test
    fun `пятого соединения не бывает`() {
        assertFalse(ConnectionPolicy.mayConnect(e, setOf(a, b, c, d), blocked = emptySet(), isOutsider = true))
    }

    @Test
    fun `с заблокированным и уже подключённым не соединяемся`() {
        assertFalse(ConnectionPolicy.mayConnect(d, setOf(a), blocked = setOf(d), isOutsider = true))
        assertFalse(ConnectionPolicy.mayConnect(a, setOf(a), blocked = emptySet(), isOutsider = true))
    }

    @Test
    fun `стучится тот, у кого member_id меньше, второй ждёт 10 секунд`() {
        assertEquals(0, ConnectionPolicy.requestDelayMs(me = a, peer = b))
        assertEquals(ConnectionPolicy.DEFERRED_REQUEST_MS, ConnectionPolicy.requestDelayMs(me = b, peer = a))
        assertEquals(10_000, ConnectionPolicy.DEFERRED_REQUEST_MS)
    }

    @Test
    fun `повтор после неудачи через 2, 4, 8 секунд и так до 30`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(ConnectionPolicy::retryDelayMs))
        assertFailsWith<IllegalArgumentException> { ConnectionPolicy.retryDelayMs(0) }
    }

    @Test
    fun `поиск постоянно включён, пока соединений меньше трёх`() {
        assertTrue(ConnectionPolicy.isDiscoveryAlwaysOn(2))
        assertFalse(ConnectionPolicy.isDiscoveryAlwaysOn(3))
    }
}
