package ru.pereklichka.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.RoomMeshStore
import ru.pereklichka.data.db.toEntity
import ru.pereklichka.data.inMemoryDatabase
import ru.pereklichka.di.NetworkModule
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.service.OwnLocation
import ru.pereklichka.status.GroupViewProvider
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SosWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val db = inMemoryDatabase()
    private val clock = FakeClock { 0 }
    private val repository = GroupRepository(db, clock)
    private val store = RoomMeshStore(db, repository, clock)

    /**
     * Вид считается на настоящем времени: виртуальное время `runTest` сработало бы на таймауте `awaitAlarmActive`,
     * пока Room отвечает из своего потока.
     */
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewProvider = GroupViewProvider(store, repository, PresenceTracker(clock), OwnLocation(), AppSettings(context), clock, viewScope)
    private var sos = EventId("")

    @Before
    fun start() = runTest {
        server.start()
        db.groupDao().insert(group("test-token").toEntity(active = true))
        sos = store.appendOwnEvent(ControlEventDraft(EventType.SOS)).id
    }

    @After
    fun close() {
        viewScope.cancel()
        server.close()
        db.close()
    }

    @Test
    fun `notified alert completes`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"notified":true}""").build())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        val request = server.takeRequest()
        assertEquals("/api/v1/alert", request.url.encodedPath)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("sos", request.headers["X-Alert-Source"])
        assertEquals("member", request.headers["X-Alert-Member-Id"])
    }

    @Test
    fun `notification failure retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"notified":false}""").build())
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    /** Пустую пачку шлёт этот телефон от своего имени, а не автор SOS. */
    @Test
    fun `unknown group sends heartbeat and retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(202).body("""{"accepted":0}""").build())

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals("/api/v1/alert", server.takeRequest().url.encodedPath)
        val heartbeat = server.takeRequest()
        assertEquals("/api/v1/events", heartbeat.url.encodedPath)
        assertEquals("""{"sender_id":"relay","events":[]}""", heartbeat.body!!.utf8())
    }

    @Test
    fun `server error retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun `captive portal page retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>Войдите в сеть</html>").build())
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun `resolved SOS completes without sending`() = runTest {
        store.appendOwnEvent(ControlEventDraft(EventType.RESOLVE, target = MemberId("member"), refs = listOf(sos), resolveKind = ResolveKind.SELF_OK))

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `replaced group completes without sending`() = runTest {
        db.groupDao().deactivate()
        db.groupDao().insert(group("other-token").copy(me = MemberId("other-member")).toEntity(active = true))

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, server.requestCount)
    }

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<SosWorker>(context)
            .setInputData(
                workDataOf(
                    SosWorker.TOKEN to "test-token",
                    SosWorker.MEMBER to "member",
                    SosWorker.SENDER to "relay",
                    SosWorker.ALARM to sos.value,
                    SosWorker.URL to server.url("/").toString(),
                ),
            )
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SosWorker(appContext, workerParameters, NetworkModule.eventsApi(NetworkModule.okHttpClient()), repository, viewProvider)
            })
            .build()
        return withContext(Dispatchers.Default) { worker.doWork() }
    }

    private fun group(token: String) =
        GroupInfo(tag = "tag-$token", token = token, name = "Поход", leaderId = MemberId("member"), me = MemberId("member"), createdAtWallMs = 0)
}
