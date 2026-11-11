package ru.pereklichka.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.AppSettings
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.data.RoomMeshStore
import ru.pereklichka.data.db.LocationPointEntity
import ru.pereklichka.data.inMemoryDatabase
import ru.pereklichka.di.NetworkModule
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.sync.ControlEventDraft
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.service.OwnLocation
import ru.pereklichka.status.GroupViewProvider
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    /** Время теста. Его двигает сам тест или сервер на каждый запрос, поэтому оно атомарное. */
    private val nowMs = AtomicLong(0)
    private val clock = FakeClock { nowMs.get() }
    private val repository = GroupRepository(db, clock)
    private val store = RoomMeshStore(db, repository, clock)
    private val uploadStatus = UploadStatus()
    private val settings = AppSettings(context)

    // Вид считается на настоящем времени: виртуальное время runTest сработало бы на таймауте awaitRoute,
    // пока Room отвечает из своего потока.
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewProvider = GroupViewProvider(store, PresenceTracker(clock), OwnLocation(), settings, clock, viewScope)
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    /** Тела всех запросов, которые получил сервер, по порядку. */
    private val batches: MutableList<EventBatchDto> = Collections.synchronizedList(mutableListOf())

    /** id точек из пачек, на которые сервер ответил 202. */
    private val acceptedIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var group: GroupInfo

    @Before
    fun setUp() = runTest {
        server.start()
        settings.setServerUrl(server.url("/").toString())
        group = repository.createGroup("Поход")
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        server.close()
        db.close()
    }

    @Test
    fun `202 помечает отправленными ровно точки пачки, пачки по 200`() = runTest {
        insertPoints(250)
        respond { batch -> accepted(batch) }

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(200, 50), batches.map { it.events.size })
        assertEquals(0, db.locationDao().unsent(1_000).size)
        assertTrue(batches.all { it.senderId == group.me.value })
        assertEquals("Bearer ${group.token}", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `503 даёт повтор и ничего не помечает`() = runTest {
        insertPoints(10)
        respond { MockResponse.Builder().code(503).body(error("unavailable")).build() }

        val result = runWorker()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(10, db.locationDao().unsent(1_000).size)
    }

    @Test
    fun `413 уменьшает пачку вдвое`() = runTest {
        insertPoints(200)
        respond { batch ->
            if (batch.events.size > 100) MockResponse.Builder().code(413).body(error("too_large")).build() else accepted(batch)
        }

        runWorker()

        assertEquals(200, batches.first().events.size)
        assertTrue(batches.drop(1).all { it.events.size <= 100 })
        assertEquals(0, db.locationDao().unsent(1_000).size)
    }

    @Test
    fun `422 находит кривую точку, помечает её rejected, остальные уходят`() = runTest {
        insertPoints(20)
        val badId = "point-13"
        respond { batch ->
            if (batch.events.any { it.id == badId }) MockResponse.Builder().code(422).body(error("invalid_event")).build()
            else accepted(batch)
        }

        runWorker()

        assertEquals(true, db.locationDao().isRejected(badId))
        assertEquals(0, db.locationDao().unsent(1_000).size)
        assertEquals((1..20).map { "point-$it" }.toSet() - badId, acceptedIds.toSet())
    }

    @Test
    fun `после кривой точки размер пачки восстанавливается`() = runTest {
        insertPoints(300)
        val badId = "point-13"
        respond { batch ->
            if (batch.events.any { it.id == badId }) MockResponse.Builder().code(422).body(error("invalid_event")).build()
            else accepted(batch)
        }

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val badBatch = batches.indexOfFirst { it.events.singleOrNull()?.id == badId }
        assertEquals(listOf(200, 87), batches.drop(badBatch + 1).map { it.events.size })
        assertEquals(true, db.locationDao().isRejected(badId))
        assertEquals(0, db.locationDao().unsent(1_000).size)
    }

    @Test
    fun `запуск длится не дольше двух минут, остаток уходит в повтор`() = runTest {
        insertPoints(1_000)
        respond { batch ->
            nowMs.addAndGet(70_000)
            accepted(batch)
        }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(listOf(200, 200), batches.map { it.events.size })
        assertEquals(600, db.locationDao().unsent(1_000).size)
    }

    @Test
    fun `портал Wi-Fi без интернета даёт повтор, а не ошибку`() = runTest {
        insertPoints(3)
        respond { MockResponse.Builder().code(200).body("<html>Войдите в сеть</html>").build() }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(3, db.locationDao().unsent(10).size)
        assertEquals(null, uploadStatus.lastError.value)
    }

    @Test
    fun `неожиданный успешный код даёт повтор`() = runTest {
        insertPoints(3)
        respond { MockResponse.Builder().code(204).build() }

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(3, db.locationDao().unsent(10).size)
    }

    @Test
    fun `heartbeat уходит, когда маршрут идёт и отправлять нечего`() = runTest {
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_STARTED))
        respond { batch -> accepted(batch) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        val heartbeat = batches.single()
        assertEquals(emptyList(), heartbeat.events)
        assertEquals(group.me.value, heartbeat.senderId)
    }

    @Test
    fun `heartbeat не чаще раза в минуту`() = runTest {
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_STARTED))
        respond { batch -> accepted(batch) }

        runWorker()
        nowMs.addAndGet(59_000)
        runWorker()
        assertEquals(1, batches.size)

        nowMs.addAndGet(2_000)
        runWorker()
        assertEquals(2, batches.size)
    }

    @Test
    fun `после отправки точек heartbeat не нужен`() = runTest {
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_STARTED))
        insertPoints(5)
        respond { batch -> accepted(batch) }

        runWorker()

        assertEquals(listOf(5), batches.map { it.events.size })
    }

    @Test
    fun `до начала маршрута heartbeat не уходит`() = runTest {
        respond { batch -> accepted(batch) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `после конца маршрута heartbeat не уходит`() = runTest {
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_STARTED))
        store.appendOwnEvent(ControlEventDraft(EventType.ROUTE_FINISHED))
        respond { batch -> accepted(batch) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `401 останавливает отправку без повторов`() = runTest {
        insertPoints(5)
        respond { MockResponse.Builder().code(401).body(error("unauthorized")).build() }

        assertEquals(ListenableWorker.Result.failure(), runWorker())
        assertEquals(5, db.locationDao().unsent(1_000).size)
        assertNotNull(uploadStatus.lastError.value, "Ошибка 401 должна попасть в диагностику")
    }

    @Test
    fun `точка с недопустимыми значениями на сервер не уходит`() = runTest {
        insertPoints(3)
        // Запись вставлена в обход маппера и потому без пометки rejected: проверка перед отправкой должна её поймать.
        db.locationDao().insertAll(listOf(entity("bad-point", seq = 4, latitude = 120.0)))
        respond { batch -> accepted(batch) }

        runWorker()

        assertTrue(batches.flatMap { it.events }.none { it.id == "bad-point" })
        assertEquals(3, batches.sumOf { it.events.size })
    }

    @Test
    fun `отправляет точки всей группы от своего имени`() = runTest {
        insertPoints(2)
        db.locationDao().insertAll(listOf(entity("other-point", 1).copy(author = "other-author")))
        respond { batch -> accepted(batch) }

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(setOf("point-1", "point-2", "other-point"), acceptedIds.toSet())
        assertTrue(batches.all { it.senderId == group.me.value })
        assertEquals("other-author", batches.flatMap { it.events }.single { it.id == "other-point" }.memberId)
        assertTrue(db.locationDao().unsent(10).isEmpty())
    }

    @Test
    fun `ошибка соединения сохраняет точки для повтора`() = runTest {
        insertPoints(2)
        settings.setServerUrl("http://127.0.0.1:1")

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(2, db.locationDao().unsent(10).size)
    }

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    UploadWorker(
                        appContext, workerParameters, db.locationDao(), repository,
                        NetworkModule.eventsApi(NetworkModule.okHttpClient()), settings, uploadStatus, viewProvider, clock,
                    )
            })
            .build()
        return withContext(Dispatchers.Default) { worker.doWork() }
    }

    private fun respond(response: (EventBatchDto) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val batch = json.decodeFromString<EventBatchDto>(request.body!!.utf8())
                batches += batch
                return response(batch)
            }
        }
    }

    private fun accepted(batch: EventBatchDto): MockResponse {
        acceptedIds += batch.events.map { it.id }
        return MockResponse.Builder().code(202).body("""{"accepted":${batch.events.size}}""").build()
    }

    private fun error(code: String) = """{"error":{"code":"$code","message":"$code"}}"""

    private suspend fun insertPoints(count: Int) {
        db.locationDao().insertAll((1..count).map { entity("point-$it", seq = it.toLong()) })
    }

    private fun entity(id: String, seq: Long, latitude: Double = 43.35) =
        LocationPointEntity(id, group.me.value, seq, latitude, 42.44, 1_794_300_000_000, 5.0, null, null, null, 80)
}
