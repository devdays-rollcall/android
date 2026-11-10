package ru.pereklichka.domain.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.sync.TransportConfig
import ru.pereklichka.domain.sync.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Виртуальный эфир: связи, задержка, потери и повторы сообщений, остановка транспорта. */
class InMemoryTransportTest {

    @Test
    fun `связь между запущенными телефонами даёт Connected обоим`() = runTest {
        val network = network()
        val alice = phone(network, "alice")
        val bob = phone(network, "bob")

        network.connect(alice.id, bob.id)

        assertEquals(listOf("connected bob"), alice.log)
        assertEquals(listOf("connected alice"), bob.log)
        assertEquals(setOf(bob.id), alice.transport.connectedPeers.value)
    }

    @Test
    fun `сообщение приходит с задержкой и только по живой связи`() = runTest {
        val network = network()
        val alice = phone(network, "alice")
        val bob = phone(network, "bob")
        network.connect(alice.id, bob.id)

        assertTrue(alice.transport.send(bob.id, byteArrayOf(42)))
        assertEquals(listOf("connected alice"), bob.log)
        advanceTimeBy(DELIVERY_MS)
        assertEquals(listOf("connected alice", "received alice 42"), bob.log)

        network.disconnect(alice.id, bob.id)
        assertFalse(alice.transport.send(bob.id, byteArrayOf(43)))
    }

    @Test
    fun `сообщение в пути не доходит, если связь оборвалась`() = runTest {
        val network = network()
        val alice = phone(network, "alice")
        val bob = phone(network, "bob")
        network.connect(alice.id, bob.id)

        alice.transport.send(bob.id, byteArrayOf(42))
        network.disconnect(alice.id, bob.id)
        network.connect(alice.id, bob.id)
        advanceTimeBy(DELIVERY_MS)

        assertEquals(listOf("connected alice", "disconnected alice", "connected alice"), bob.log)
    }

    @Test
    fun `потеря и дублирование по команде теста`() = runTest {
        val network = network()
        val alice = phone(network, "alice")
        val bob = phone(network, "bob")
        network.connect(alice.id, bob.id)

        network.lossRate = 1.0
        assertTrue(alice.transport.send(bob.id, byteArrayOf(1)))
        network.lossRate = 0.0
        network.duplicateRate = 1.0
        alice.transport.send(bob.id, byteArrayOf(2))
        advanceTimeBy(DELIVERY_MS)

        assertEquals(listOf("connected alice", "received alice 2", "received alice 2"), bob.log)
    }

    @Test
    fun `остановка рвёт все связи, а к остановленному не подключиться`() = runTest {
        val network = network()
        val alice = phone(network, "alice")
        val bob = phone(network, "bob")
        network.connect(alice.id, bob.id)

        bob.transport.stop()
        network.connect(alice.id, bob.id)

        assertEquals(listOf("connected bob", "disconnected bob"), alice.log)
        assertFalse(network.isConnected(alice.id, bob.id))
        assertEquals(emptySet(), alice.transport.connectedPeers.value)
    }

    private fun TestScope.network() = InMemoryNetwork(backgroundScope, { testScheduler.currentTime })

    /** Запущенный виртуальный телефон, который записывает события своего транспорта в [Phone.log]. */
    private fun TestScope.phone(network: InMemoryNetwork, name: String): Phone {
        val phone = Phone(MemberId(name), network.transport(MemberId(name)))
        phone.transport.start(TransportConfig("service", phone.id, MutableStateFlow(emptySet())) { false })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            phone.transport.events.collect { event ->
                phone.log += when (event) {
                    is TransportEvent.Connected -> "connected ${event.peer}"
                    is TransportEvent.Disconnected -> "disconnected ${event.peer}"
                    is TransportEvent.Received -> "received ${event.peer} ${event.bytes.joinToString()}"
                }
            }
        }
        return phone
    }

    private class Phone(val id: MemberId, val transport: InMemoryTransport) {
        val log = mutableListOf<String>()
    }

    private companion object {
        /** С запасом больше задержки сети. `advanceUntilIdle` не годится: он не ждёт задачи `backgroundScope`. */
        const val DELIVERY_MS = 5_000L
    }
}
