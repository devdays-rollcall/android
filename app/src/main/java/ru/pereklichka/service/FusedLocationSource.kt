package ru.pereklichka.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LatLon
import javax.inject.Inject

/**
 * [LocationSource] на Fused Location Provider из Google Play Services. Подписывается на него [TrackRecorder].
 *
 * Без разрешения на точную геолокацию или при отказе Play Services поток не падает, а ждёт [RETRY_MS] и пробует
 * снова. Служба для той же группы сеанс не перезапускает, поэтому GPS включится сам, когда человек выдаст разрешение.
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
class FusedLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS).build()

    override val fixes: Flow<Fix> = flow {
        while (true) {
            if (hasFineLocation()) {
                emitAll(updates().catch { Log.w(TAG, "GPS не запустился: ${it.message}") })
            }
            delay(RETRY_MS)
        }
    }

    /** Обновления до отписки. Ошибка запуска закрывает поток исключением, его ловит [fixes]. */
    @SuppressLint("MissingPermission") // Разрешение проверяет hasFineLocation перед подпиской.
    private fun updates(): Flow<Fix> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    trySend(location.toFix())
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close(it) }
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и запросом.
            close(e)
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "FusedLocationSource"
        const val INTERVAL_MS = 5_000L
        const val RETRY_MS = 10_000L
    }
}

internal fun Location.toFix() = Fix(
    position = LatLon(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    timeUtcMs = time,
    elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000,
)
