package ru.pereklichka.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.LatLon
import javax.inject.Inject

/**
 * [LocationSource] на Fused Location Provider из Google Play Services. Подписывается на него [TrackRecorder].
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
class FusedLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()

    private fun Location.toFix(): Fix = Fix(
        position = LatLon(latitude, longitude),
        accuracyM = (if (hasAccuracy()) accuracy.toDouble() else null),
        altitudeM = if (hasAltitude()) altitude else null,
        speedMps = (if (hasSpeed()) speed.toDouble() else null),
        bearingDeg = (if (hasBearing()) bearing.toDouble() else null),
        timeUtcMs = time,
        elapsedRealtimeMs = elapsedRealtimeNanos / 1_000_000,
    )


    override val fixes: Flow<Fix> = callbackFlow {
        // TODO(Василиса): callbackFlow поверх requestLocationUpdates, PRIORITY_HIGH_ACCURACY раз в 5 с, отписка в awaitClose.
        //  Необязательные поля Fix заполнять, только если hasAccuracy() и прочие вернули true.
        //  docs/team/vasilisa-service-storage.md

            // Проверка разрешения — при каждой подписке, с актуальным состоянием
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                close(SecurityException("ACCESS_FINE_LOCATION not granted"))
                return@callbackFlow
            }

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        trySend(location.toFix())
                    }
                }
            }

            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    .addOnFailureListener { e -> close(e) }
            } catch (e: SecurityException) {
                close(e)
            }

            awaitClose {
                client.removeLocationUpdates(callback)
            }
    }
}
