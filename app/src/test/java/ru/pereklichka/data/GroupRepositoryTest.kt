package ru.pereklichka.data

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.db.LocationPointEntity
import ru.pereklichka.data.db.key
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Создание группы и вступление: новый `member_id`, повторное вступление, переход прежней группы в прошлые маршруты
 * без потери записей, удаление и сводка прошлых маршрутов для стартового экрана.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupRepositoryTest {

    private val db = inMemoryDatabase()
    private var nowMs = 0L
    private val clock = FakeClock { nowMs }
    private val repository = GroupRepository(db, clock)
    private val store = RoomMeshStore(db, repository, clock)
    private val history = RouteHistory(db)
    private val qr = QrPayload(GroupToken.generate(), "Поход", MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c"))

    @After
    fun close() = db.close()

    @Test
    fun `создание группы даёт новый member_id, и я руководитель`() = runTest {
        assertNull(repository.activeGroup())

        val first = repository.createGroup("Поход")
        val second = repository.createGroup("Ещё поход")

        assertEquals(first.me, first.leaderId)
        assertNotEquals(first.me, second.me)
        assertEquals(GroupToken.tagOf(second.token), second.tag)
        assertEquals(second, repository.activeGroup())
    }

    @Test
    fun `повторное вступление в ту же группу ничего не меняет`() = runTest {
        val joined = repository.join(qr)
        db.locationDao().insertAll(listOf(point(joined)))

        val again = repository.join(qr.copy(groupName = "Другое имя"))

        assertEquals(joined, again)
        assertEquals(1, db.locationDao().unsent(joined.key, 10).size)
        assertEquals(emptyList(), history.pastRoutes.first())
    }

    @Test
    fun `вступление в другую группу оставляет прежнюю прошлым маршрутом с её записями`() = runTest {
        val joined = repository.join(qr)
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Оля"))
        db.locationDao().insertAll(listOf(point(joined)))

        val other = repository.join(qr.copy(token = GroupToken.generate()))

        assertNotEquals(joined.me, other.me)
        assertEquals(other, repository.activeGroup())
        assertEquals(1, db.locationDao().unsent(joined.key, 10).size)
        assertEquals(0, db.locationDao().unsent(other.key, 10).size)
        assertEquals(listOf(joined), history.pastRoutes.first().map { it.group })
        assertEquals(1, history.snapshot(joined.key).first()?.events?.size)
    }

    @Test
    fun `переход в прошлые маршруты снимает активную группу, а трек ждёт отправки`() = runTest {
        val joined = repository.join(qr)
        db.locationDao().insertAll(listOf(point(joined, "waiting", 1), point(joined, "sent", 2).copy(uploaded = true)))

        repository.archiveActiveGroup()

        assertNull(repository.activeGroup())
        assertTrue(repository.hasUnsentTrack())
        assertEquals(listOf(joined), repository.pastGroupsWithUnsentTrack())
        assertEquals(listOf("waiting"), db.locationDao().unsent(joined.key, 10).map { it.id })
    }

    @Test
    fun `прошлый маршрут без неотправленных точек отправлять не нужно`() = runTest {
        val joined = repository.join(qr)
        db.locationDao().insertAll(listOf(point(joined).copy(uploaded = true)))

        repository.createGroup("Новый поход")

        assertEquals(emptyList(), repository.pastGroupsWithUnsentTrack())
        assertFalse(repository.hasUnsentTrack())
    }

    @Test
    fun `повторное вступление по тому же QR после выхода даёт отдельную группу`() = runTest {
        val first = repository.join(qr)
        store.appendOwnEvent(ControlEventDraft(EventType.LEFT))
        repository.archiveActiveGroup()

        val second = repository.join(qr)

        assertNotEquals(first.me, second.me)
        assertEquals(first.token, second.token)
        assertEquals(listOf(first), history.pastRoutes.first().map { it.group })
        assertEquals(emptyList(), history.snapshot(second.key).first()?.events)
    }

    @Test
    fun `удаление прошлого маршрута стирает его записи и не трогает активную группу`() = runTest {
        val old = repository.join(qr)
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Оля"))
        db.locationDao().insertAll(listOf(point(old)))
        val active = repository.createGroup("Новый поход")
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Оля"))

        repository.deletePastGroup(old.key)
        repository.deletePastGroup(active.key)

        assertEquals(emptyList(), history.pastRoutes.first())
        assertNull(history.snapshot(old.key).first())
        assertEquals(0, db.locationDao().unsent(old.key, 10).size)
        assertEquals(active, repository.activeGroup())
        assertEquals(1, history.snapshot(active.key).first()?.events?.size)
    }

    @Test
    fun `удаление активной группы стирает её целиком`() = runTest {
        val group = repository.createGroup("Поход")
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Аня"))

        repository.deleteActiveGroup()

        assertNull(repository.activeGroup())
        assertNull(history.snapshot(group.key).first())
        assertEquals(emptyList(), history.pastRoutes.first())
    }

    /** `FakeClock` отсчитывает настенное время от 10 ноября 2026 года, это 1_794_268_800_000 мс. */
    @Test
    fun `сводка прошлого маршрута берёт начало и конец от руководителя по часам телефона`() = runTest {
        val group = repository.createGroup("Эльбрус")
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Аня"))
        nowMs += 60_000
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_STARTED))
        nowMs += 3_600_000
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_FINISHED))
        db.locationDao().insertAll(listOf(point(group, "a", 1), point(group, "b", 2).copy(uploaded = true)))

        repository.archiveActiveGroup()
        val route = history.pastRoutes.first().single()

        assertEquals(group, route.group)
        assertEquals(1_794_268_860_000, route.startedAtWallMs)
        assertEquals(1_794_272_460_000, route.finishedAtWallMs)
        assertEquals(1, route.members)
        assertEquals(2, route.points)
        assertEquals(1, route.unsentPoints)
        assertFalse(route.iLeft)
    }

    @Test
    fun `сводка помнит, что я вышел из группы`() = runTest {
        repository.join(qr)
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Оля"))
        store.appendOwnEvent(ControlEventDraft(EventType.LEFT))

        repository.archiveActiveGroup()
        val route = history.pastRoutes.first().single()

        assertTrue(route.iLeft)
        assertNull(route.startedAtWallMs)
        assertNull(route.finishedAtWallMs)
    }

    private fun point(group: GroupInfo, id: String = "p", seq: Long = 1) =
        LocationPointEntity(group.key, id, "bob", seq, 43.35, 42.44, 0, null, null, null, null, null)
}
