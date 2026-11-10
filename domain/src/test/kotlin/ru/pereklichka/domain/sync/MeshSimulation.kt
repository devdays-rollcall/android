package ru.pereklichka.domain.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.testing.InMemoryMeshStore
import ru.pereklichka.domain.testing.InMemoryNetwork
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Группа виртуальных телефонов для [MeshSimulationTest]: у каждого свои часы, хранилище, транспорт и настоящий
 * [SyncEngine]. Повторяет устройство исполняемой модели протокола: человек идёт и пишет точку
 * каждые 12,5 с, своё состояние раз в 15 с, при первом запуске записывает JOINED.
 */
class MeshSimulation(private val test: TestScope, seed: Int) {

    private val config = SyncConfig()
    private val codec = MeshCodec(config)
    private val random = Random(seed)

    val network = InMemoryNetwork(test.backgroundScope, { test.testScheduler.currentTime }, seed)
    val phones = mutableListOf<SimPhone>()

    /** Пока виртуальное время меньше этого, телефоны пишут точки трека. */
    var trackUntil: Duration = Duration.INFINITE

    val now: Duration get() = test.testScheduler.currentTime.milliseconds

    fun phones(vararg names: String): List<SimPhone> = names.map { SimPhone(it).also(phones::add) }

    fun chain(vararg chain: SimPhone) = chain.toList().zipWithNext { a, b -> network.connect(a.id, b.id) }

    fun fullMesh(vararg mesh: SimPhone) {
        for ((index, a) in mesh.withIndex()) mesh.drop(index + 1).forEach { b -> network.connect(a.id, b.id) }
    }

    fun runFor(duration: Duration) = test.advanceTimeBy(duration)

    /** Ждёт [what], проверяя условие каждые 50 мс виртуального времени. Возвращает, сколько пришлось ждать. */
    suspend fun waitUntil(what: String, timeout: Duration, condition: suspend () -> Boolean): Duration {
        val start = now
        while (!condition()) {
            if (now - start > timeout) fail("Не дождались за $timeout: $what")
            runFor(50.milliseconds)
        }
        return now - start
    }

    /** У всех телефонов одинаковые события и точки по каждому автору. */
    suspend fun converged(): Boolean = phones.map { it.streams() }.distinct().size == 1

    /**
     * У каждого телефона по каждому автору непрерывный префикс и ни одного повторного UUID.
     * Нарушение значит, что хранилище приняло запись не по правилам.
     */
    suspend fun assertPrefixesIntact() {
        for (phone in phones) {
            val streams = phone.streams()
            for ((stream, records) in streams) {
                assertEquals((1L..records.size).toList(), records.map { it.first }, "Дыра в префиксе: $stream у ${phone.name}")
            }
            val ids = streams.values.flatten().map { it.second }
            assertEquals(ids.size, ids.toSet().size, "Повторный UUID у ${phone.name}")
        }
    }

    /** Каждая связь между телефонами появляется и пропадает через случайные 5–60 с до момента [until], как в модели. */
    fun flapLinks(until: Duration) {
        for ((index, first) in phones.withIndex()) {
            for (second in phones.drop(index + 1)) {
                test.backgroundScope.launch {
                    delay(random.nextLong(0, 30_000))
                    while (now < until) {
                        if (network.isConnected(first.id, second.id)) network.disconnect(first.id, second.id)
                        else network.connect(first.id, second.id)
                        delay(random.nextLong(5_000, 60_000))
                    }
                }
            }
        }
    }

    /** [count] тревог в случайные моменты до [until] от случайных телефонов о случайных участниках. */
    fun raiseRandomAlarms(count: Int, until: Duration) {
        repeat(count) {
            val author = phones.random(random)
            val target = phones.random(random)
            val at = random.nextLong(60_000, until.inWholeMilliseconds)
            test.backgroundScope.launch {
                delay(at)
                if (author.isRunning) author.raiseAlarm(about = target)
            }
        }
    }

    inner class SimPhone(val name: String) {
        val id = MemberId(name)
        private val clock = FakeClock { test.testScheduler.currentTime }.apply {
            // Часы телефонов расходятся до 10 минут, на результат это влиять не должно.
            wallShiftMs = random.nextLong(-600_000, 600_000)
        }
        private val group = GroupInfo(tag = "sim", token = "token", name = "Симуляция", leaderId = MemberId("A"), me = id,
            createdAtWallMs = 0)
        private val store = InMemoryMeshStore(group, clock)

        /** Хранилище, через которое работает движок: считает точки, пришедшие повторно, то есть лишний трафик. */
        val countingStore = DuplicatePointCounter(store)
        private val transport = network.transport(id)
        private var presence = PresenceTracker(clock)
        private var engine = newEngine()
        private var generators: Job? = null
        private var position = LatLon(43.35 + random.nextDouble() / 100, 42.44 + random.nextDouble() / 100)

        val isRunning: Boolean get() = generators != null

        fun start() {
            engine.start(test.backgroundScope, group)
            transport.start(TransportConfig("ru.pereklichka.g.sim", id, MutableStateFlow(emptySet())) { false })
            generators = test.backgroundScope.launch {
                if (store.summary().control[id] == null) store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = name))
                launch { repeatEvery(12_500) { if (now < trackUntil) writePoint() } }
                launch { repeatEvery(15_000) { writeState() } }
            }
        }

        /** Перезагрузка: телефон выключен [downtime], потом всё в памяти начинается заново, а хранилище на месте. */
        fun reboot(downtime: Duration) {
            generators?.cancel()
            generators = null
            engine.stop()
            transport.stop()
            test.backgroundScope.launch {
                delay(downtime)
                clock.reboot()
                presence = PresenceTracker(clock)
                engine = newEngine()
                start()
            }
        }

        suspend fun raiseAlarm(about: SimPhone): ControlEvent =
            store.appendOwnEvent(ControlEventDraft(EventType.ALARM, target = about.id))

        suspend fun sos(): ControlEvent = store.appendOwnEvent(ControlEventDraft(EventType.SOS))

        suspend fun hasEvent(id: EventId): Boolean = store.snapshot().first().events.any { it.value.id == id }

        suspend fun pointCount(author: SimPhone): Long = store.summary().locations[author.id] ?: 0

        /** Номера и UUID записей каждого потока («события A», «точки B») по порядку номеров. */
        suspend fun streams(): Map<String, List<Pair<Long, EventId>>> {
            val summary = store.summary()
            val control = summary.control.keys.associate { author ->
                "события $author" to store.controlEvents(author, 0, Int.MAX_VALUE).map { it.value.seq to it.value.id }
            }
            val points = summary.locations.keys.associate { author ->
                "точки $author" to store.locations(author, 0, Int.MAX_VALUE).map { it.seq to it.id }
            }
            return control + points
        }

        private fun newEngine() = SyncEngine(countingStore, transport, presence, codec, clock, config)

        private suspend fun writePoint() {
            // Около 15 м на север за 12,5 с.
            position = position.copy(latitude = position.latitude + 0.000135)
            val fix = Fix(position, accuracyM = 5.0, altitudeM = 2_100.0, speedMps = 1.2, bearingDeg = 0.0,
                timeUtcMs = clock.wallMs(), elapsedRealtimeMs = clock.elapsedMs())
            store.appendOwnLocation(fix, batteryPercent = 80)
        }

        private suspend fun writeState() {
            store.putOwnState(MemberStateDraft(fix = null, batteryPercent = 80, heardAgoMs = presence.heardAgo()))
        }

        private suspend fun repeatEvery(periodMs: Long, action: suspend () -> Unit) {
            delay(random.nextLong(0, 5_000))
            while (test.backgroundScope.isActive) {
                action()
                delay(periodMs)
            }
        }
    }

    companion object {
        /** Реальное время на один сценарий для runTest: часовой сценарий считается секунды, но не мгновенно. */
        val TEST_TIMEOUT = 5.minutes
    }
}

/** Обёртка хранилища, которая считает повторно пришедшие точки: так тест видит лишний трафик. */
class DuplicatePointCounter(private val store: MeshStore) : MeshStore by store {
    var duplicatePoints = 0
        private set

    override suspend fun apply(delta: IncomingDelta): ApplyResult {
        val known = store.summary().locations
        duplicatePoints += delta.locations.count { it.seq <= (known[it.author] ?: 0) }
        return store.apply(delta)
    }
}
