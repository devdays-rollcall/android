package ru.pereklichka.domain.sync

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.testing.InMemoryMeshStore
import ru.pereklichka.domain.testing.InMemoryNetwork
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Запуск и остановка движка. Поведение сеанса проверяют `SyncEngineSessionTest` и `MeshSimulationTest`. */
class SyncEngineLifecycleTest {

    private val me = MemberId("me")
    private val group = GroupInfo("tag", "token", "Тест", leaderId = me, me = me, createdAtWallMs = 0)

    @Test
    fun `повторный запуск без остановки запрещён`() = runTest {
        val engine = engine()
        engine.start(backgroundScope, group)

        assertFailsWith<IllegalStateException> { engine.start(backgroundScope, group) }
        engine.stop()
    }

    @Test
    fun `остановка отменяет корутины движка, и его можно запустить снова`() = runTest {
        val engine = engine()

        engine.start(backgroundScope, group)
        runCurrent()
        assertTrue(engine.diagnostics.value.running)
        engine.stop()
        assertFalse(engine.diagnostics.value.running)

        // Если бы таймер Hello пережил остановку, он вызвал бы обработчик и тест упал бы.
        advanceTimeBy(SyncConfig().helloIntervalMs * 3)

        engine.start(backgroundScope, group)
        assertTrue(engine.diagnostics.value.running)
        engine.stop()
    }

    private fun TestScope.engine(): SyncEngine {
        val clock = FakeClock { testScheduler.currentTime }
        val config = SyncConfig()
        return SyncEngine(
            store = InMemoryMeshStore(group, clock),
            transport = InMemoryNetwork(backgroundScope, { testScheduler.currentTime }).transport(me),
            presence = PresenceTracker(clock),
            codec = MeshCodec(config),
            clock = clock,
            config = config,
        )
    }
}
