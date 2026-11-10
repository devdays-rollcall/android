package ru.pereklichka.actions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.GroupToken
import ru.pereklichka.data.inMemoryDatabase
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.testing.InMemoryMeshStore
import ru.pereklichka.service.OwnLocation
import ru.pereklichka.status.GroupViewProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Действия поверх настоящих `GroupRepository` (Room в памяти), `InMemoryMeshStore` и `GroupViewProvider`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupActionsTest {

    private val db = inMemoryDatabase()
    private val clock = FakeClock { 1_000_000 }
    private val repository = GroupRepository(db, clock)
    private lateinit var viewProvider: GroupViewProvider

    @After
    fun close() = db.close()

    @Test
    fun `руководитель начинает маршрут и перекличку`() = runTest {
        val group = repository.createGroup("Поход")
        val (actions, store) = actionsFor(group)

        actions.startRoute()
        actions.startRollcall()

        assertEquals(listOf(EventType.ROUTE_STARTED, EventType.ROLLCALL), ownEvents(store, group).map { it.type })
    }

    @Test
    fun `участнику действия руководителя недоступны`() = runTest {
        val group = repository.join(QrPayload(GroupToken.generate(), "Поход", MemberId.random()))
        val (actions, _) = actionsFor(group)

        assertFailsWith<IllegalStateException> { actions.startRoute() }
        assertFailsWith<IllegalStateException> { actions.finishRoute() }
        assertFailsWith<IllegalStateException> { actions.startRollcall() }
    }

    @Test
    fun `повторное вступление в ту же группу не пишет второй JOINED`() = runTest {
        val qr = QrPayload(GroupToken.generate(), "Поход", MemberId.random())
        val group = repository.join(qr)
        val (actions, store) = actionsFor(group)

        actions.joinGroup(qr, "Оля")
        actions.joinGroup(qr, "Оля")

        assertEquals(listOf("Оля"), ownEvents(store, group).map { it.name })
    }

    @Test
    fun `отлучка пишется в миллисекундах, отмена длительностью 0`() = runTest {
        val group = repository.createGroup("Поход")
        val (actions, store) = actionsFor(group)

        actions.announceAway(10)
        actions.cancelAway()

        assertEquals(listOf(600_000L, 0L), ownEvents(store, group).map { it.durationMs })
        assertFailsWith<IllegalArgumentException> { actions.announceAway(0) }
    }

    @Test
    fun `снятие ссылается на все тревоги о человеке, которые видны на экране`() = runTest {
        val group = repository.createGroup("Поход")
        val (actions, store) = actionsFor(group)
        val ann = MemberId("ann")
        val bob = MemberId("bob")
        store.appendOwnEvent(ControlEventDraft(EventType.JOINED, name = "Я"))
        actions.raiseAlarm(ann)
        actions.raiseAlarm(ann)
        actions.raiseAlarm(bob)
        val alarmsAboutAnn = ownEvents(store, group).filter { it.target == ann }.map { it.id }
        viewProvider.view.first { it?.alarms?.size == 3 }

        actions.resolve(ann, ResolveKind.SEEN_OK)

        val resolve = ownEvents(store, group).last()
        assertEquals(EventType.RESOLVE, resolve.type)
        assertEquals(ann, resolve.target)
        assertEquals(alarmsAboutAnn.toSet(), resolve.refs.toSet())
    }

    @Test
    fun `без активных тревог снятие ничего не пишет`() = runTest {
        val group = repository.createGroup("Поход")
        val (actions, store) = actionsFor(group)

        actions.resolve(MemberId("ann"), ResolveKind.SELF_OK)

        assertEquals(emptyList(), ownEvents(store, group))
    }

    private fun TestScope.actionsFor(group: GroupInfo): Pair<GroupActions, InMemoryMeshStore> {
        val store = InMemoryMeshStore(group, clock)
        viewProvider = GroupViewProvider(
            store = store,
            presence = PresenceTracker(clock),
            ownLocation = OwnLocation(),
            settings = AppSettings(ApplicationProvider.getApplicationContext()),
            clock = clock,
            scope = backgroundScope,
        )
        return GroupActions(repository, store, viewProvider) to store
    }

    private suspend fun ownEvents(store: InMemoryMeshStore, group: GroupInfo): List<ControlEvent> =
        store.controlEvents(group.me, afterSeq = 0, limit = 100).map { it.value }
}
