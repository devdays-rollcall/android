package ru.pereklichka.domain.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.domain.time.Stamped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Общий набор проверок для любой реализации [MeshStore]: правила приёма из `03-sync-protocol.md`,
 * свои записи, выборки, поток изменений и снимок.
 *
 * Наследники создают хранилище своей реализации: `InMemoryMeshStoreContractTest` в `domain`
 * и `RoomMeshStoreContractTest` в `app`. Каждый тест получает новое пустое хранилище.
 */
abstract class MeshStoreContractTest {

    /** Новое пустое хранилище активной группы [group]. */
    protected abstract suspend fun createStore(group: GroupInfo, clock: Clock): MeshStore

    /** Помечена ли точка в хранилище из последнего [createStore] как непригодная для сервера. */
    protected abstract suspend fun isRejected(pointId: EventId): Boolean

    private val me = MemberId("me")
    private val bob = MemberId("bob")
    private val group = GroupInfo(tag = "tag", token = "token", name = "Тест", leaderId = me, me = me, createdAtWallMs = 0)

    private var nowMs = 1_000L
    private val clock = FakeClock { nowMs }

    private fun storeTest(body: suspend TestScope.(MeshStore) -> Unit) = runTest { body(createStore(group, clock)) }

    @Test
    fun `свои события нумеруются подряд с единицы`() = storeTest { store ->
        val events = List(3) { store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я")) }

        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        assertEquals(3, events.map { it.id }.toSet().size)
        assertTrue(events.all { it.author == me })
        assertEquals(mapOf(me to 3L), store.summary().control)
        assertEquals(events, store.controlEvents(me, afterSeq = 0, limit = 10).map { it.value })
    }

    @Test
    fun `у событий и точек свои счётчики`() = storeTest { store ->
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я"))
        store.appendOwnEvent(ControlEventDraft(EventType.SOS))
        val point = store.appendOwnLocation(fix(), batteryPercent = null)

        assertEquals(1, point.seq)
        assertEquals(mapOf(me to 2L), store.summary().control)
        assertEquals(mapOf(me to 1L), store.summary().locations)
    }

    @Test
    fun `принимает только ровно следующий номер`() = storeTest { store ->
        assertEquals(ApplyResult(outOfOrder = 1), store.apply(IncomingDelta(control = listOf(remote(bob, 2)))))
        assertEquals(ApplyResult(newControl = 1), store.apply(IncomingDelta(control = listOf(remote(bob, 1)))))
        assertEquals(ApplyResult(outOfOrder = 1), store.apply(IncomingDelta(control = listOf(remote(bob, 3)))))
        assertEquals(
            ApplyResult(newControl = 2),
            store.apply(IncomingDelta(control = listOf(remote(bob, 2), remote(bob, 3)))),
        )

        assertEquals(mapOf(bob to 3L), store.summary().control)
        assertEquals(listOf(1L, 2L, 3L), store.controlEvents(bob, 0, 10).map { it.value.seq })
    }

    @Test
    fun `записи одного автора в Delta принимаются в любом порядке`() = storeTest { store ->
        val result = store.apply(
            IncomingDelta(
                control = listOf(remote(bob, 3), remote(bob, 1), remote(bob, 2)),
                locations = listOf(point(bob, 2), point(bob, 1)),
            ),
        )

        assertEquals(ApplyResult(newControl = 3, newLocations = 2), result)
    }

    @Test
    fun `повторная доставка отбрасывается как повтор`() = storeTest { store ->
        val delta = IncomingDelta(control = listOf(remote(bob, 1)), locations = listOf(point(bob, 1)))
        store.apply(delta)

        assertEquals(ApplyResult(duplicates = 2), store.apply(delta))
        assertEquals(1, store.controlEvents(bob, 0, 10).size)
        assertEquals(1, store.locations(bob, 0, 10).size)
    }

    @Test
    fun `тот же номер с другим id это конфликт, остаётся первая запись`() = storeTest { store ->
        val first = remote(bob, 1, id = EventId("first"))
        store.apply(IncomingDelta(control = listOf(first)))

        val result = store.apply(IncomingDelta(control = listOf(remote(bob, 1, id = EventId("second")))))

        assertEquals(ApplyResult(conflicts = 1), result)
        assertEquals(listOf(first.value), store.controlEvents(bob, 0, 10).map { it.value })
    }

    @Test
    fun `запись с номером меньше единицы отбрасывается`() = storeTest { store ->
        val result = store.apply(
            IncomingDelta(control = listOf(remote(bob, 0)), locations = listOf(point(bob, -1))),
        )

        assertEquals(ApplyResult(outOfOrder = 2), result)
        assertEquals(SyncSummary(emptyMap(), emptyMap(), emptyMap()), store.summary())
    }

    @Test
    fun `свои записи от других не принимаются`() = storeTest { store ->
        val mine = store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я"))

        val echo = store.apply(IncomingDelta(control = listOf(Stamped.received(mine, 500, clock))))
        val forged = store.apply(IncomingDelta(control = listOf(remote(me, 2)), states = listOf(remoteState(me, 7))))

        assertEquals(ApplyResult(duplicates = 1), echo)
        assertEquals(ApplyResult(conflicts = 2), forged)
        assertEquals(mapOf(me to 1L), store.summary().control)
        assertEquals(emptyMap(), store.summary().states)
    }

    @Test
    fun `точка с недопустимыми значениями хранится с пометкой и не рвёт префикс`() = storeTest { store ->
        val outOfRange = point(bob, 1, latitude = 200.0)
        // SQLite хранит NaN как NULL, поэтому такая точка требует от Room отдельного внимания.
        val notANumber = point(bob, 2, latitude = Double.NaN)
        val good = point(bob, 3)

        val result = store.apply(IncomingDelta(locations = listOf(outOfRange, notANumber, good)))

        assertEquals(ApplyResult(newLocations = 3), result)
        assertTrue(isRejected(outOfRange.id))
        assertTrue(isRejected(notANumber.id))
        assertFalse(isRejected(good.id))
        assertEquals(listOf(outOfRange, notANumber, good), store.locations(bob, 0, 10))
    }

    @Test
    fun `событие незнакомого типа хранится и не рвёт префикс`() = storeTest { store ->
        val unknown = remote(bob, 1, type = EventType.UNKNOWN)
        val next = remote(bob, 2, type = EventType.SOS)

        assertEquals(ApplyResult(newControl = 2), store.apply(IncomingDelta(control = listOf(unknown, next))))
        assertEquals(
            listOf(EventType.UNKNOWN, EventType.SOS),
            store.snapshot().first().events.filter { it.value.author == bob }.sortedBy { it.value.seq }
                .map { it.value.type },
        )
    }

    @Test
    fun `своя точка повторяет фиксацию`() = storeTest { store ->
        val fix = fix()

        val point = store.appendOwnLocation(fix, batteryPercent = 77)

        assertEquals(me, point.author)
        assertEquals(fix.position, LatLon(point.latitude, point.longitude))
        assertEquals(fix.timeUtcMs, point.recordedAtMs)
        assertEquals(fix.accuracyM, point.accuracyM)
        assertEquals(fix.altitudeM, point.altitudeM)
        assertEquals(fix.speedMps, point.speedMps)
        assertEquals(fix.bearingDeg, point.bearingDeg)
        assertEquals(77, point.batteryPercent)
        assertEquals(listOf(point), store.locations(me, 0, 10))
        assertFalse(isRejected(point.id))
    }

    @Test
    fun `состояние заменяется только более новым`() = storeTest { store ->
        assertEquals(ApplyResult(newStates = 1), store.apply(IncomingDelta(states = listOf(remoteState(bob, 5)))))
        assertEquals(ApplyResult(duplicates = 1), store.apply(IncomingDelta(states = listOf(remoteState(bob, 3)))))
        assertEquals(ApplyResult(newStates = 1), store.apply(IncomingDelta(states = listOf(remoteState(bob, 6)))))

        assertEquals(mapOf(bob to 6L), store.summary().states)
        assertEquals(listOf(6L), store.statesNewerThan(mapOf(bob to 5L)).map { it.value.seq })
        assertEquals(emptyList(), store.statesNewerThan(mapOf(bob to 6L)))
        assertEquals(listOf(bob), store.statesNewerThan(emptyMap()).map { it.value.member })
    }

    @Test
    fun `своё состояние нумеруется подряд и несёт возраст фиксации`() = storeTest { store ->
        val fix = fix(elapsedRealtimeMs = clock.elapsedMs() - 3_000)
        val heard = mapOf(bob to 4_000L)

        val first = store.putOwnState(MemberStateDraft(fix, batteryPercent = 50, heardAgoMs = heard))
        val second = store.putOwnState(MemberStateDraft(fix = null, batteryPercent = null, heardAgoMs = emptyMap()))

        assertEquals(MemberState(me, 1, fix.position, fix.accuracyM, fixAgeMs = 3_000, batteryPercent = 50, heardAgoMs = heard), first)
        assertEquals(MemberState(me, 2), second)
        assertEquals(mapOf(me to 2L), store.summary().states)
    }

    @Test
    fun `отметка свежести хранит возраст в момент получения`() = storeTest { store ->
        nowMs = 50_000
        store.apply(IncomingDelta(control = listOf(remote(bob, 1, ageMs = 5_000))))
        val own = store.appendOwnEvent(ControlEventDraft(EventType.SOS))

        val events = store.snapshot().first().events.associateBy { it.value.author }
        val remoteStamp = events.getValue(bob)
        assertEquals(5_000, remoteStamp.ageAtReceiptMs)
        assertEquals(clock.elapsedMs(), remoteStamp.receivedAtElapsedMs)
        assertEquals(clock.bootId(), remoteStamp.receivedBootId)
        assertEquals(0, events.getValue(me).ageAtReceiptMs)
        assertEquals(own, events.getValue(me).value)
    }

    @Test
    fun `выборки учитывают начальный номер и предел`() = storeTest { store ->
        store.apply(
            IncomingDelta(
                control = (1L..5L).map { remote(bob, it) },
                locations = (1L..5L).map { point(bob, it) },
            ),
        )

        assertEquals(listOf(3L, 4L), store.controlEvents(bob, afterSeq = 2, limit = 2).map { it.value.seq })
        assertEquals(listOf(5L), store.locations(bob, afterSeq = 4, limit = 10).map { it.seq })
        assertEquals(emptyList(), store.locations(bob, afterSeq = 5, limit = 10))
        assertEquals(emptyList(), store.controlEvents(MemberId("nobody"), afterSeq = 0, limit = 10))
    }

    @Test
    fun `изменения приходят только когда что-то добавилось`() = storeTest { store ->
        val changes = mutableListOf<StoreChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.changes.toList(changes) }

        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я"))
        store.putOwnState(MemberStateDraft(fix = null, batteryPercent = null, heardAgoMs = emptyMap()))
        store.appendOwnLocation(fix(), batteryPercent = null)
        val delta = IncomingDelta(
            control = listOf(remote(bob, 1)),
            states = listOf(remoteState(bob, 1)),
            locations = listOf(point(bob, 1)),
        )
        store.apply(delta)
        store.apply(delta)

        assertEquals(
            listOf(
                StoreChange.OWN_EVENT,
                StoreChange.OWN_STATE,
                StoreChange.OWN_LOCATION,
                StoreChange.REMOTE_EVENTS,
                StoreChange.REMOTE_STATES,
                StoreChange.REMOTE_LOCATIONS,
            ),
            changes,
        )
    }

    @Test
    fun `снимок содержит группу, события и состояния`() = storeTest { store ->
        val joined = store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я"))
        store.apply(IncomingDelta(states = listOf(remoteState(bob, 1)), locations = listOf(point(bob, 1))))

        val snapshot = store.snapshot().first()

        assertEquals(group, snapshot.group)
        assertEquals(listOf(joined), snapshot.events.map { it.value })
        assertEquals(setOf(bob), snapshot.states.keys)
    }

    @Test
    fun `одновременные apply не смешиваются`() = storeTest { store ->
        val delta = IncomingDelta(
            control = (1L..50L).map { remote(bob, it) },
            locations = (1L..300L).map { point(bob, it) },
        )

        val first = async(Dispatchers.Default) { store.apply(delta) }
        val second = async(Dispatchers.Default) { store.apply(delta) }
        val total = first.await() + second.await()

        assertEquals(ApplyResult(newControl = 50, newLocations = 300, duplicates = 350), total)
        assertEquals((1L..300L).toList(), store.locations(bob, 0, 1_000).map { it.seq })
    }

    private fun remote(
        author: MemberId,
        seq: Long,
        type: EventType = EventType.JOINED,
        id: EventId = EventId("$author-c$seq"),
        ageMs: Long = 0,
    ): Stamped<ControlEvent> {
        val event = ControlEvent(id, author, seq, createdAtWallMs = 0, type = type)
        return Stamped.received(event, ageMs, clock)
    }

    private fun remoteState(member: MemberId, seq: Long): Stamped<MemberState> =
        Stamped.received(MemberState(member, seq), 0, clock)

    private fun point(author: MemberId, seq: Long, latitude: Double = 43.35): LocationPoint =
        LocationPoint(EventId("$author-l$seq"), author, seq, latitude, longitude = 42.44, recordedAtMs = 0)

    private fun fix(elapsedRealtimeMs: Long = 0) = Fix(
        position = LatLon(43.35, 42.44),
        accuracyM = 8.5,
        altitudeM = 2100.0,
        speedMps = 1.2,
        bearingDeg = 90.0,
        timeUtcMs = 1_794_300_000_000,
        elapsedRealtimeMs = elapsedRealtimeMs,
    )
}
