package ru.pereklichka.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Сценарии из исполняемой модели протокола на настоящем [SyncEngine] поверх `InMemoryTransport`
 * и `InMemoryMeshStore`. Если сценарий падает, сверь движок с `docs/03-sync-protocol.md`.
 *
 * Пороги по времени взяты с запасом относительно результатов модели:
 * тест ловит поломку правил, а не мерит скорость. В каждом сценарии в конце проверяется непрерывный префикс.
 */
@Ignore("включи, когда сделаешь обработчики SyncEngine")
class MeshSimulationTest {

    @Test
    fun `цепочка A-B-C-D - тревога A доходит до D`() = simulation(seed = 1) {
        val (a, b, c, d) = phones("A", "B", "C", "D")
        phones.forEach { it.start() }
        chain(a, b, c, d)
        trackUntil = 12.minutes
        runFor(10.minutes)

        val alarm = a.raiseAlarm(about = d)
        waitUntil("тревога A у D", timeout = 1.seconds) { d.hasEvent(alarm.id) }

        runFor(14.minutes - now)
        assertTrue(converged(), "Цепочка не сошлась")
        assertPrefixesIntact()
    }

    @Test
    fun `разрыв и восстановление цепочки - всё досылается без дублей`() = simulation(seed = 2) {
        val (a, b, c, d) = phones("A", "B", "C", "D")
        phones.forEach { it.start() }
        chain(a, b, c, d)
        trackUntil = 13.minutes
        runFor(5.minutes)

        network.disconnect(b.id, c.id)
        runFor(1.minutes)
        val sos = d.sos()
        runFor(4.minutes)
        network.connect(b.id, c.id)

        waitUntil("SOS от D у A после восстановления связи", timeout = 1.seconds) { a.hasEvent(sos.id) }
        runFor(15.minutes - now)
        assertTrue(converged(), "После восстановления связи данные не сошлись")
        assertPrefixesIntact()
    }

    @Test
    fun `тревога приходит раньше накопленных точек`() = simulation(seed = 3) {
        val group = phones("A", "B", "C", "D", "E", "F")
        val (a, b, c) = group.take(3)
        val (d, e, f) = group.drop(3)
        phones.forEach { it.start() }
        fullMesh(a, b, c)
        fullMesh(d, e, f)
        trackUntil = 60.minutes
        runFor(60.minutes)

        network.connect(b.id, e.id)
        runFor(1.seconds)
        val alarm = f.raiseAlarm(about = a)
        waitUntil("тревога F у всех", timeout = 1.seconds) { phones.all { it.hasEvent(alarm.id) } }

        assertTrue(a.pointCount(f) < f.pointCount(f), "Точки F уже были у A, сценарий ничего не проверил")
        assertPrefixesIntact()
    }

    @Test
    fun `после встречи двух половин группы всё сходится`() = simulation(seed = 4) {
        val group = phones("A", "B", "C", "D", "E", "F")
        val (a, b, c) = group.take(3)
        val (d, e, f) = group.drop(3)
        phones.forEach { it.start() }
        fullMesh(a, b, c)
        fullMesh(d, e, f)
        trackUntil = 60.minutes
        runFor(60.minutes)

        network.connect(b.id, e.id)

        // В модели половины сходятся за 9 с, а без флага more за 28 с: порог отделяет одно от другого.
        waitUntil("сходимость половин", timeout = 20.seconds) { converged() }
        assertPrefixesIntact()
    }

    @Test
    fun `при потере 5 процентов сообщений стабильная цепочка сходится без переподключения`() {
        for (seed in 1..3) {
            simulation(seed) {
                val (a, b, c, d) = phones("A", "B", "C", "D")
                network.lossRate = 0.05
                phones.forEach { it.start() }
                chain(a, b, c, d)
                trackUntil = 25.minutes
                runFor(30.minutes)

                assertTrue(converged(), "Прогон $seed: данные не сошлись, где-то застрял поток автора")
                assertTrue(
                    network.isConnected(a.id, b.id) && network.isConnected(b.id, c.id) && network.isConnected(c.id, d.id),
                    "Прогон $seed: в цепочке рвалось соединение",
                )
                assertPrefixesIntact()
            }
        }
    }

    @Test
    fun `новичок с тремя соседями догоняет историю без лавины повторов`() = simulation(seed = 5) {
        val (a, b, c, d) = phones("A", "B", "C", "D")
        listOf(a, b, c).forEach { it.start() }
        fullMesh(a, b, c)
        trackUntil = 60.minutes
        runFor(60.minutes)

        d.start()
        fullMesh(a, b, c, d)
        waitUntil("новичок догнал историю", timeout = 30.seconds) { converged() }

        val needed = listOf(a, b, c).sumOf { d.pointCount(it) }
        val duplicateRatio = d.countingStore.duplicatePoints.toDouble() / needed
        assertTrue(duplicateRatio < 1.0, "Лишних точек на одну нужную: $duplicateRatio")
        assertPrefixesIntact()
    }

    @Test
    fun `в полной сетке точки автора не идут через соседей`() = simulation(seed = 6) {
        val (a, b, c) = phones("A", "B", "C")
        phones.forEach { it.start() }
        fullMesh(a, b, c)
        trackUntil = 60.minutes
        runFor(61.minutes)

        val needed = phones.sumOf { phone -> phones.filter { it != phone }.sumOf { phone.pointCount(it) } }
        val duplicates = phones.sumOf { it.countingStore.duplicatePoints }
        assertTrue(converged(), "Полная сетка не сошлась")
        // Каждый слышит каждого, поэтому точки автора приходят только от него самого. Без правила
        // «не слать точки автора тому, кто слышит его сам» черновой движок в этой симуляции давал 5 % лишних точек.
        assertTrue(duplicates.toDouble() / needed < 0.02, "Лишних точек $duplicates на $needed нужных")
        assertPrefixesIntact()
    }

    @Test
    fun `связи мигают, сообщения теряются и дублируются, телефон перезагружается - данные сходятся`() =
        simulation(seed = 7) {
            val (a, b, c, d, e) = phones("A", "B", "C", "D", "E")
            network.lossRate = 0.05
            network.duplicateRate = 0.05
            network.reorderRate = 0.05
            phones.forEach { it.start() }
            val flappingEnd = 30.minutes
            trackUntil = flappingEnd
            flapLinks(until = flappingEnd)
            raiseRandomAlarms(count = 20, until = flappingEnd)
            runFor(15.minutes)

            c.reboot(downtime = 1.minutes)
            runFor(flappingEnd - now + 1.seconds)
            network.lossRate = 0.0
            network.duplicateRate = 0.0
            network.reorderRate = 0.0
            fullMesh(a, b, c, d, e)

            waitUntil("сходимость после стабилизации связей", timeout = 2.minutes) { converged() }
            assertPrefixesIntact()
        }

    private fun simulation(seed: Int, scenario: suspend MeshSimulation.() -> Unit) =
        runTest(timeout = MeshSimulation.TEST_TIMEOUT) { MeshSimulation(this, seed).scenario() }
}
