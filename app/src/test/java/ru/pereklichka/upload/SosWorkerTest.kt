package ru.pereklichka.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.di.NetworkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SosWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun close() = server.close()

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

    @Test
    fun `unknown group sends heartbeat and retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(202).body("""{"accepted":0}""").build())

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals("/api/v1/alert", server.takeRequest().url.encodedPath)
        val heartbeat = server.takeRequest()
        assertEquals("/api/v1/events", heartbeat.url.encodedPath)
        assertTrue(heartbeat.body!!.utf8().contains("\"events\":[]"))
    }

    @Test
    fun `server error retries`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<SosWorker>(context)
            .setInputData(workDataOf(SosWorker.TOKEN to "test-token", SosWorker.MEMBER to "member", SosWorker.URL to server.url("/").toString()))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SosWorker(appContext, workerParameters, NetworkModule.eventsApi(NetworkModule.okHttpClient()))
            })
            .build()
        return worker.doWork()
    }
}
