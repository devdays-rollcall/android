package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.GroupSnapshot
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.time.Age
import ru.pereklichka.domain.time.Stamped
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила статусов [GroupCalculator] и тревог [activeAlarms]. Отдельно проверено, что тревоги, перекличка
 * и маршрут не зависят от порядка событий, повторов и настенных часов.
 */
class GroupCalculatorTest {

    private var nowMs = 10 * 60 * 60_000L
    private val clock = FakeClock { nowMs }
    private val calculator = GroupCalculator(Thresholds())

    private val leader = MemberId("L")
    private val a = MemberId("A")
    private val b = MemberId("B")
    private val c = MemberId("C")
    private val d = MemberId("D")
    private val everyone = listOf(leader, a, b, c, d)

    @Test
    fun `состав группы из JOINED, я первым, руководитель из GroupInfo`() {
        val history = History().apply {
            joinAll()
            event(MemberId("stranger"), EventType.UNKNOWN)
        }

        val view = compute(history, me = b)

        assertEquals(b, view.me)
        assertEquals(b, view.members.first().id)
        assertEquals(everyone.toSet(), view.members.map { it.id }.toSet())
        assertEquals("Имя A", view.members.single { it.id == a }.name)
        assertTrue(view.members.single { it.id == leader }.isLeader)
        assertTrue(view.members.single { it.id == b }.isMe)
        assertFalse(view.iAmLeader)
        assertTrue(compute(history, me = leader).iAmLeader)
    }

    @Test
    fun `в списке сначала SOS, потом тревога, «Не слышно», отлучка и остальные`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            event(a, EventType.SOS)
            state(b, ageMs = 1_000)
            event(leader, EventType.ALARM, target = b)
            event(d, EventType.AWAY, ageMs = 60_000, durationMs = 10 * 60_000)
        }

        assertEquals(listOf(a, b, c, d, leader), compute(history).members.map { it.id })
    }

    @Test
    fun `участник становится «Не слышно» после порога`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            state(a, ageMs = 119_000)
        }

        assertEquals(Presence.OK, presenceOf(a, compute(history)))
        nowMs += 2_000
        assertEquals(Presence.LOST, presenceOf(a, compute(history)))
    }

    @Test
    fun `в режиме демо порог «Не слышно» 60 секунд`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            state(a, ageMs = 61_000)
        }

        assertEquals(Presence.OK, presenceOf(a, compute(history)))
        assertEquals(Presence.LOST, presenceOf(a, GroupCalculator(Thresholds.DEMO).compute(history.snapshot(), emptyMap(), null, clock)))
    }

    @Test
    fun `себя телефон не считает «Не слышно»`() {
        val history = History().apply { joinAll(ageMs = 60 * 60_000) }

        assertEquals(Presence.OK, presenceOf(leader, compute(history)))
    }

    @Test
    fun `своя отлучка видна в своей строке`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            event(leader, EventType.AWAY, ageMs = 60_000, durationMs = 10 * 60_000)
        }

        val mine = compute(history).members.single { it.isMe }
        assertEquals(Presence.AWAY, mine.presence)
        assertEquals(9 * 60_000L, mine.awayRemainingMs)
    }

    @Test
    fun `у своей строки нет расстояния и направления до себя`() {
        val history = History().apply {
            joinAll()
            state(leader, ageMs = 10_000, position = LatLon(43.01, 42.0))
        }
        val myFix = Fix(LatLon(43.0, 42.0), 5.0, null, null, null, timeUtcMs = 0, elapsedRealtimeMs = clock.elapsedMs())

        val mine = compute(history, myFix = myFix).members.single { it.isMe }
        assertEquals(LatLon(43.01, 42.0), mine.position)
        assertNull(mine.distanceM)
        assertNull(mine.bearingDeg)
    }

    @Test
    fun `имя берётся из JOINED с наибольшим номером при любом порядке событий`() {
        val history = History().apply {
            joinAll()
            event(a, EventType.JOINED, name = "Новое имя")
        }

        assertEquals("Новое имя", compute(history).members.single { it.id == a }.name)
        history.events.reverse()
        assertEquals("Новое имя", compute(history).members.single { it.id == a }.name)
    }

    @Test
    fun `отлучка подавляет «Не слышно» до конца срока, длительность 0 её отменяет`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            event(a, EventType.AWAY, ageMs = 5 * 60_000, durationMs = 10 * 60_000)
        }

        val away = compute(history).members.single { it.id == a }
        assertEquals(Presence.AWAY, away.presence)
        assertEquals(5 * 60_000L, away.awayRemainingMs)

        nowMs += 6 * 60_000
        assertEquals(Presence.LOST, presenceOf(a, compute(history)))

        val cancelled = History().apply {
            joinAll(ageMs = 60 * 60_000)
            event(a, EventType.AWAY, ageMs = 5 * 60_000, durationMs = 10 * 60_000)
            event(a, EventType.AWAY, ageMs = 4 * 60_000, durationMs = 0)
        }
        assertEquals(Presence.LOST, presenceOf(a, compute(cancelled)))
        assertNull(compute(cancelled).members.single { it.id == a }.awayRemainingMs)
    }

    @Test
    fun `последний контакт это минимум наблюдений, и видно, кто слышал последним`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            event(a, EventType.ROLLCALL_ANSWER, ageMs = 100_000)
            state(a, ageMs = 90_000)
            state(b, ageMs = 10_000, heardAgoMs = mapOf(a to 20_000L))
        }

        val viaB = compute(history).members.single { it.id == a }
        assertEquals(Age(30_000, approximate = false), viaB.lastContact)
        assertEquals(b, viaB.lastHeardBy)
        assertFalse(viaB.heardDirectlyNow)

        val direct = compute(history, heardAgo = mapOf(a to 5_000L)).members.single { it.id == a }
        assertEquals(Age(5_000, approximate = false), direct.lastContact)
        assertEquals(leader, direct.lastHeardBy)
        assertTrue(direct.heardDirectlyNow)
    }

    @Test
    fun `позиция, точность, заряд, расстояние и направление берутся из состояния`() {
        val north = LatLon(43.01, 42.0)
        val east = LatLon(43.0, 42.01)
        val history = History().apply {
            joinAll()
            state(a, ageMs = 10_000, position = north, accuracyM = 7.0, fixAgeMs = 3_000, batteryPercent = 55)
            state(b, ageMs = 10_000, position = east)
        }
        val myFix = Fix(LatLon(43.0, 42.0), 5.0, null, null, null, timeUtcMs = 0, elapsedRealtimeMs = clock.elapsedMs())

        val view = compute(history, myFix = myFix)

        val memberA = view.members.single { it.id == a }
        assertEquals(north, memberA.position)
        assertEquals(Age(13_000, approximate = false), memberA.positionAge)
        assertEquals(7.0, memberA.accuracyM)
        assertEquals(55, memberA.batteryPercent)
        assertEquals(1_111.95, assertNotNull(memberA.distanceM), 1.0)
        assertEquals(0.0, assertNotNull(memberA.bearingDeg), 0.5)
        assertEquals(90.0, assertNotNull(view.members.single { it.id == b }.bearingDeg), 0.5)
        assertNull(view.members.single { it.id == c }.distanceM)
        assertNull(compute(history, myFix = null).members.single { it.id == a }.distanceM)
    }

    @Test
    fun `состояние маршрута меняют только события руководителя`() {
        val history = History().apply { joinAll() }
        assertEquals(RouteState.LOBBY, compute(history).route)

        history.event(a, EventType.ROUTE_STARTED)
        assertEquals(RouteState.LOBBY, compute(history).route)

        history.event(leader, EventType.ROUTE_STARTED)
        history.event(a, EventType.ROUTE_FINISHED)
        assertEquals(RouteState.ACTIVE, compute(history).route)

        history.event(leader, EventType.ROUTE_FINISHED)
        assertEquals(RouteState.FINISHED, compute(history).route)
    }

    @Test
    fun `тревога снимается только действительным снятием со ссылкой на неё`() {
        val history = History().apply { joinAll() }
        val alarm = history.event(b, EventType.ALARM, target = a)
        val invalid = listOf(
            resolve(author = a, target = a, kind = ResolveKind.SELF_OK, refs = emptyList()),
            resolve(author = c, target = a, kind = ResolveKind.SELF_OK, refs = listOf(alarm.id)),
            resolve(author = a, target = a, kind = ResolveKind.SEEN_OK, refs = listOf(alarm.id)),
            resolve(author = d, target = a, kind = ResolveKind.FALSE_ALARM, refs = listOf(alarm.id)),
            resolve(author = c, target = d, kind = ResolveKind.SEEN_OK, refs = listOf(alarm.id)),
        )
        val valid = listOf(
            resolve(author = a, target = a, kind = ResolveKind.SELF_OK, refs = listOf(alarm.id)),
            resolve(author = c, target = a, kind = ResolveKind.SEEN_OK, refs = listOf(alarm.id)),
            resolve(author = b, target = a, kind = ResolveKind.FALSE_ALARM, refs = listOf(alarm.id)),
            resolve(author = leader, target = a, kind = ResolveKind.FALSE_ALARM, refs = listOf(alarm.id)),
        )

        for (resolve in invalid) {
            assertEquals(listOf(alarm.id), compute(history.with(resolve)).alarms.map { it.id }, "Сработало $resolve")
        }
        for (resolve in valid) {
            assertEquals(emptyList(), compute(history.with(resolve)).alarms, "Не сработало $resolve")
        }
    }

    @Test
    fun `время маршрута считается от событий руководителя`() {
        val history = History().apply { joinAll() }
        history.event(leader, EventType.ROUTE_STARTED, ageMs = 600_000)

        assertEquals(Age(600_000, false), compute(history).routeStartedAgo)
        assertEquals(null, compute(history).routeFinishedAgo)

        history.event(leader, EventType.ROUTE_FINISHED, ageMs = 60_000)
        assertEquals(Age(60_000, false), compute(history).routeFinishedAgo)
    }

    @Test
    fun `тревога показывает, о ком она и кто её объявил`() {
        val history = History().apply { joinAll() }
        val alarm = history.event(b, EventType.ALARM, target = a, ageMs = 4_000)

        val view = compute(history)

        assertEquals(AlarmView(alarm.id, alarm.seq, a, "Имя A", b, "Имя B", isSos = false, Age(4_000, false)), view.alarms.single())
        assertTrue(view.members.single { it.id == a }.hasActiveAlarm)
        assertFalse(view.members.single { it.id == b }.hasActiveAlarm)
    }

    @Test
    fun `SOS это тревога о себе, снимает её сам участник`() {
        val history = History().apply { joinAll() }
        val sos = history.event(a, EventType.SOS)

        val view = compute(history)
        val alarm = view.alarms.single()
        assertEquals(sos.id, alarm.id)
        assertTrue(alarm.isSos)
        assertEquals(a, alarm.target)
        assertTrue(view.members.single { it.id == a }.hasSos)

        val resolved = history.with(resolve(author = a, target = a, kind = ResolveKind.SELF_OK, refs = listOf(sos.id)))
        assertEquals(emptyList(), compute(resolved).alarms)
    }

    @Test
    fun `снятие, пришедшее раньше тревоги, закрывает её сразу`() {
        val history = History().apply { joinAll() }
        val alarm = history.event(leader, EventType.ALARM, target = a)
        history.event(a, EventType.RESOLVE, target = a, refs = listOf(alarm.id), kind = ResolveKind.SELF_OK)

        assertEquals(emptyList(), compute(history.without(alarm.id)).alarms)
        val resolveFirst = history.snapshot().copy(events = history.events.reversed())
        assertEquals(emptyList(), calculator.compute(resolveFirst, emptyMap(), null, clock).alarms)
    }

    @Test
    fun `две тревоги об одном человеке - снятие закрывает только ту, что видел снимающий`() {
        val history = History().apply { joinAll() }
        val byLeader = history.event(leader, EventType.ALARM, target = a)
        val byB = history.event(b, EventType.ALARM, target = a)
        history.event(a, EventType.RESOLVE, target = a, refs = listOf(byB.id), kind = ResolveKind.SELF_OK)

        assertEquals(listOf(byLeader.id), compute(history).alarms.map { it.id })
    }

    @Test
    fun `радиоконтакт тревогу не снимает, но даёт подсказку о близости`() {
        val history = History().apply { joinAll() }
        history.event(b, EventType.ALARM, target = a)
        history.state(a, ageMs = 1_000)

        val view = compute(history, heardAgo = mapOf(a to 1_000L))

        assertEquals(1, view.alarms.size)
        assertEquals(listOf(NearbyPrompt(a, "Имя A")), view.prompts)
    }

    @Test
    fun `подсказки нет, если человек не слышен напрямую или тревога обо мне`() {
        val history = History().apply { joinAll() }
        history.event(b, EventType.ALARM, target = a)

        assertEquals(emptyList(), compute(history, heardAgo = mapOf(a to 40_000L)).prompts)
        assertEquals(emptyList(), compute(history, me = a, heardAgo = mapOf(a to 1_000L)).prompts)
    }

    @Test
    fun `перекличка - ответили, рядом но молчат, не слышно`() {
        val history = History().apply {
            joinAll(ageMs = 60 * 60_000)
            everyone.forEach { state(it, ageMs = 5_000) }
            state(c, ageMs = 5 * 60_000)
        }
        val older = history.event(leader, EventType.ROLLCALL, ageMs = 10 * 60_000)
        val rollcall = history.event(leader, EventType.ROLLCALL, ageMs = 30_000)
        history.event(a, EventType.ROLLCALL_ANSWER, refs = listOf(rollcall.id))
        history.event(b, EventType.ROLLCALL_ANSWER, refs = listOf(older.id))
        history.event(d, EventType.ROLLCALL, ageMs = 1_000)

        val view = assertNotNull(compute(history).rollcall)

        assertEquals(rollcall.id, view.id)
        assertEquals(150_000L, view.remainingMs)
        assertEquals(listOf(a), view.answered)
        assertEquals(setOf(b, d), view.silentNearby.toSet())
        assertEquals(listOf(c), view.notHeard)

        nowMs += 151_000
        assertNull(compute(history).rollcall)
    }

    @Test
    fun `результат не зависит от порядка событий и повторной доставки`() {
        val random = Random(1)
        repeat(100) {
            val history = randomHistory(random)
            val heardAgo = mapOf(a to 3_000L, c to 50_000L)
            val base = essence(compute(history, heardAgo = heardAgo))

            repeat(20) {
                val shuffled = history.events.shuffled(random)
                val duplicated = shuffled + List(15) { history.events.random(random) }
                val snapshot = history.snapshot().copy(events = duplicated)
                assertEquals(base, essence(calculator.compute(snapshot, heardAgo, null, clock)))
            }
        }
    }

    @Test
    fun `результат не зависит от настенных часов`() {
        val random = Random(2)
        repeat(100) {
            val history = randomHistory(random)
            val base = compute(history)

            clock.wallShiftMs = random.nextLong(-600_000, 600_000)
            val shiftedEvents = history.events.map { stamped ->
                stamped.copy(
                    value = stamped.value.copy(createdAtWallMs = random.nextLong(0, 10_000_000_000_000)),
                    receivedAtWallMs = random.nextLong(0, 10_000_000_000_000),
                )
            }
            val shifted = calculator.compute(history.snapshot().copy(events = shiftedEvents), emptyMap(), null, clock)

            assertEquals(base, shifted)
            clock.wallShiftMs = 0
        }
    }

    /** То, что не должно зависеть от порядка и повторов событий при тех же `heardAgo` и часах, без учёта порядка в списках. */
    private data class Essence(
        val route: RouteState,
        val alarms: Set<EventId>,
        val rollcall: List<Set<MemberId>>?,
        val presence: Map<MemberId, Presence>,
        val prompts: Set<MemberId>,
    )

    private fun essence(view: GroupView) = Essence(
        route = view.route,
        alarms = view.alarms.map { it.id }.toSet(),
        rollcall = view.rollcall?.let { listOf(it.answered.toSet(), it.silentNearby.toSet(), it.notHeard.toSet()) },
        presence = view.members.associate { it.id to it.presence },
        prompts = view.prompts.map { it.member }.toSet(),
    )

    /** Случайная история: авторы ссылаются только на то, что уже было. */
    private fun randomHistory(random: Random): History {
        val history = History()
        history.joinAll(ageMs = 60 * 60_000)
        history.event(leader, EventType.ROUTE_STARTED, ageMs = 50 * 60_000)
        everyone.forEach { history.state(it, ageMs = random.nextLong(0, 200_000)) }
        repeat(40) {
            val author = everyone.random(random)
            val ageMs = random.nextLong(0, 10 * 60_000)
            val roll = random.nextDouble()
            val alarms = history.events.map { it.value }.filter { it.type == EventType.ALARM || it.type == EventType.SOS }
            when {
                roll < 0.25 -> history.event(author, EventType.ALARM, ageMs = ageMs, target = (everyone - author).random(random))
                roll < 0.32 -> history.event(author, EventType.SOS, ageMs = ageMs)
                roll < 0.65 && alarms.isNotEmpty() -> {
                    val seen = alarms.filter { random.nextDouble() < 0.7 }.ifEmpty { listOf(alarms.random(random)) }
                    val target = targetOf(seen.random(random))
                    history.event(
                        author, EventType.RESOLVE, ageMs = ageMs, target = target,
                        refs = seen.filter { targetOf(it) == target }.map { it.id },
                        kind = ResolveKind.entries.random(random),
                    )
                }
                roll < 0.75 && author == leader -> history.event(author, EventType.ROLLCALL, ageMs = ageMs)
                roll < 0.9 -> history.events.lastOrNull { it.value.type == EventType.ROLLCALL }?.let { rollcall ->
                    history.event(author, EventType.ROLLCALL_ANSWER, ageMs = ageMs, refs = listOf(rollcall.value.id))
                }
            }
        }
        return history
    }

    private fun targetOf(alarm: ControlEvent): MemberId = alarm.target ?: alarm.author

    private fun presenceOf(member: MemberId, view: GroupView) = view.members.single { it.id == member }.presence

    private fun compute(
        history: History,
        me: MemberId = leader,
        heardAgo: Map<MemberId, Long> = emptyMap(),
        myFix: Fix? = null,
    ): GroupView = calculator.compute(history.snapshot(me), heardAgo, myFix, clock)

    private fun resolve(author: MemberId, target: MemberId, kind: ResolveKind, refs: List<EventId>) =
        Pending(author, EventType.RESOLVE, target = target, refs = refs, kind = kind)

    /** Набор событий и состояний, из которого собирается снимок. Номера в потоке автора выдаются подряд. */
    private inner class History {
        val events = mutableListOf<Stamped<ControlEvent>>()
        private val states = mutableMapOf<MemberId, Stamped<MemberState>>()
        private val seqs = mutableMapOf<MemberId, Long>()

        fun joinAll(ageMs: Long = 0) = everyone.forEach { event(it, EventType.JOINED, ageMs = ageMs, name = "Имя $it") }

        fun event(
            author: MemberId,
            type: EventType,
            ageMs: Long = 0,
            target: MemberId? = null,
            refs: List<EventId> = emptyList(),
            name: String? = null,
            durationMs: Long? = null,
            kind: ResolveKind? = null,
        ): ControlEvent {
            val seq = (seqs[author] ?: 0) + 1
            seqs[author] = seq
            val event = ControlEvent(
                EventId("$author$seq"), author, seq, createdAtWallMs = clock.wallMs() - ageMs, type, target, refs, name,
                durationMs, kind,
            )
            events += Stamped.received(event, ageMs, clock)
            return event
        }

        fun state(
            member: MemberId,
            ageMs: Long,
            heardAgoMs: Map<MemberId, Long> = emptyMap(),
            position: LatLon? = null,
            accuracyM: Double? = null,
            fixAgeMs: Long? = null,
            batteryPercent: Int? = null,
        ) {
            val seq = (states[member]?.value?.seq ?: 0) + 1
            val state = MemberState(member, seq, position, accuracyM, fixAgeMs ?: position?.let { 0 }, batteryPercent, heardAgoMs)
            states[member] = Stamped.received(state, ageMs, clock)
        }

        /** Копия истории с ещё одним событием. */
        fun with(pending: Pending): History = copy().also {
            it.event(pending.author, pending.type, target = pending.target, refs = pending.refs, kind = pending.kind)
        }

        /** Копия истории без события [id]: как будто оно ещё не дошло. */
        fun without(id: EventId): History = copy().also { it.events.removeAll { stamped -> stamped.value.id == id } }

        fun snapshot(me: MemberId = leader) = GroupSnapshot(
            group = GroupInfo("tag", "token", "Поход", leaderId = leader, me = me, createdAtWallMs = 0),
            events = events.toList(),
            states = states.toMap(),
        )

        private fun copy(): History = History().also {
            it.events += events
            it.states += states
            it.seqs += seqs
        }
    }

    /** Событие, которое тест добавит к копии истории. */
    private class Pending(
        val author: MemberId,
        val type: EventType,
        val target: MemberId?,
        val refs: List<EventId>,
        val kind: ResolveKind?,
    ) {
        override fun toString() = "$type от $author о $target, ${kind ?: ""}, ссылки $refs"
    }
}
