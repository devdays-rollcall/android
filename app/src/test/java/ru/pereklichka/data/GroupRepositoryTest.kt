package ru.pereklichka.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.db.LocationPointEntity
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Создание группы и вступление: новый `member_id`, повторное вступление и очистка данных при смене группы. */
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

    private fun point() = LocationPointEntity("p", "bob", 1, 43.35, 42.44, 0, null, null, null, null, null)
}
