package ru.pereklichka.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.db.LocationPointEntity
import ru.pereklichka.data.db.OutboxGroupEntity
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Создание группы и вступление: новый `member_id`, повторное вступление, очистка данных при смене группы и выходе,
 * перенос неотправленного трека в очередь стёртых групп.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupRepositoryTest {

    private val db = inMemoryDatabase()
    private val repository = GroupRepository(db, FakeClock { 0 })
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
        db.locationDao().insertAll(listOf(point()))

        val again = repository.join(qr.copy(groupName = "Другое имя"))

        assertEquals(joined, again)
        assertEquals(1, db.locationDao().unsent(10).size)
    }

    @Test
    fun `вступление в другую группу очищает данные и меняет member_id`() = runTest {
        val joined = repository.join(qr)
        db.locationDao().insertAll(listOf(point()))

        val other = repository.join(qr.copy(token = GroupToken.generate()))

        assertNotEquals(joined.me, other.me)
        assertEquals(qr.leaderId, other.leaderId)
        assertEquals(0, db.locationDao().unsent(10).size)
    }

    @Test
    fun `выход из группы стирает группу и её данные`() = runTest {
        repository.join(qr)
        db.locationDao().insertAll(listOf(point()))

        repository.leaveGroup()

        assertNull(repository.activeGroup())
        assertEquals(0, db.locationDao().unsent(10).size)
    }

    @Test
    fun `стирание группы переносит неотправленные точки в очередь с её токеном`() = runTest {
        val joined = repository.join(qr)
        db.locationDao().insertAll(
            listOf(point("waiting", 1), point("sent", 2).copy(uploaded = true), point("bad", 3).copy(rejected = true)),
        )

        repository.leaveGroup()

        assertEquals(listOf("waiting"), db.outboxDao().points(joined.tag, 10).map { it.id })
        assertEquals(listOf(OutboxGroupEntity(joined.tag, qr.token, joined.me.value)), db.outboxDao().groups())
        assertTrue(repository.hasQueuedTrack())
    }

    @Test
    fun `группа без неотправленных точек в очередь не попадает`() = runTest {
        repository.join(qr)
        db.locationDao().insertAll(listOf(point().copy(uploaded = true)))

        repository.createGroup("Новый поход")

        assertEquals(emptyList(), db.outboxDao().groups())
        assertFalse(repository.hasQueuedTrack())
    }

    private fun point(id: String = "p", seq: Long = 1) =
        LocationPointEntity(id, "bob", seq, 43.35, 42.44, 0, null, null, null, null, null)
}
