package ru.pereklichka.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OwnLocationStoreTest {
    private val db = inMemoryDatabase()
    private val clock = FakeClock { 0 }
    private val repository = GroupRepository(db, clock)
    private val store = RoomMeshStore(db, repository, clock)

    @After
    fun close() = db.close()

    @Test
    fun `coordinates are saved with the current participant and sequential ids`() = runTest {
        val group = repository.createGroup("Поход")
        val fix = Fix(LatLon(43.35, 42.44), 5.0, null, null, null, 1_794_300_000_000, 0)

        val first = store.appendOwnLocation(fix, 80)
        val second = store.appendOwnLocation(fix, null)

        assertEquals(group.me, first.author)
        assertEquals(group.me, second.author)
        assertEquals(listOf(1L, 2L), db.locationDao().unsent(10).map { it.seq })
        assertEquals(listOf(first.id.value, second.id.value), db.locationDao().unsent(10).map { it.id })
    }
}
