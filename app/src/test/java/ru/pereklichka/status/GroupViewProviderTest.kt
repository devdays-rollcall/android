package ru.pereklichka.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.RoomMeshStore
import ru.pereklichka.data.inMemoryDatabase
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.service.OwnLocation
import kotlin.test.assertEquals

/** Вид группы идёт за активной группой: появляется с ней и пропадает, когда «Новая группа» её стирает. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GroupViewProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    private val clock = FakeClock { 0 }
    private val repository = GroupRepository(db, clock)

    // Room отвечает из своего потока, поэтому вид считается на настоящем времени, а не в виртуальном runTest.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val provider = GroupViewProvider(
        RoomMeshStore(db, repository, clock), repository, PresenceTracker(clock), OwnLocation(), AppSettings(context), clock, scope,
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `после стирания группы вид становится null`() = runBlocking {
        val subscription = scope.launch { provider.view.collect { } }
        val group = repository.createGroup("Поход")
        withTimeout(5_000) { provider.view.first { it?.group?.tag == group.tag } }

        repository.leaveGroup()

        withTimeout(5_000) { provider.view.first { it == null } }
        assertEquals(null, provider.view.value)
        subscription.cancel()
    }
}
