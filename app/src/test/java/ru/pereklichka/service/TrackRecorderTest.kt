package ru.pereklichka.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.domain.testing.FakeClock
import ru.pereklichka.domain.testing.InMemoryMeshStore
import ru.pereklichka.domain.track.LocationSanitizer
import ru.pereklichka.domain.track.TrackThinner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GPS по состоянию маршрута: прогрев в «Сборе», трек на маршруте, выключение после. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrackRecorderTest {

    private val me = MemberId("me")
    private val clock = FakeClock { 0 }
    private val store = InMemoryMeshStore(GroupInfo("tag", "token", "Поход", me, me, 0), clock)
    private val ownLocation = OwnLocation()
    private val gps = FakeLocationSource()
    private val route = MutableStateFlow(RouteState.LOBBY)
    private val recorder = TrackRecorder(
        locationSource = gps,
        sanitizer = LocationSanitizer(),
        thinner = TrackThinner(),
        store = store,
        ownLocation = ownLocation,
        battery = BatteryLevel(ApplicationProvider.getApplicationContext()),
    )

    @Test
    fun `в сборе GPS работает, но точки трека не пишутся`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.run(route) }

        gps.emit(fix(elapsedMs = 0))

        assertTrue(gps.isOn)
        assertEquals(fix(elapsedMs = 0), ownLocation.lastFix.value)
        assertEquals(emptyMap(), store.summary().locations)
    }

    @Test
    fun `на маршруте прореженные фиксации идут в трек`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.run(route) }
        route.value = RouteState.ACTIVE

        gps.emit(fix(elapsedMs = 0))
        gps.emit(fix(elapsedMs = 5_000))
        gps.emit(fix(elapsedMs = 60_000))

        assertEquals(mapOf(me to 2L), store.summary().locations)
    }

    @Test
    fun `фиксация с недопустимыми координатами не доходит ни до позиции, ни до трека`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.run(route) }
        route.value = RouteState.ACTIVE

        gps.emit(fix(elapsedMs = 0).copy(position = LatLon(Double.NaN, 42.44)))

        assertEquals(null, ownLocation.lastFix.value)
        assertEquals(emptyMap(), store.summary().locations)
    }

    @Test
    fun `после завершения маршрута GPS выключается`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recorder.run(route) }
        assertTrue(gps.isOn)

        route.value = RouteState.FINISHED

        assertFalse(gps.isOn)
    }

    private fun fix(elapsedMs: Long) = Fix(LatLon(43.35, 42.44), 5.0, null, null, null, timeUtcMs = 0, elapsedRealtimeMs = elapsedMs)

    /** GPS, который тест кормит фиксациями вручную и по которому видно, есть ли подписка. */
    private class FakeLocationSource : LocationSource {
        private val source = MutableSharedFlow<Fix>()
        var isOn = false
            private set

        override val fixes: Flow<Fix> = flow {
            isOn = true
            try {
                emitAll(source)
            } finally {
                isOn = false
            }
        }

        suspend fun emit(fix: Fix) = source.emit(fix)
    }
}
