package ru.pereklichka.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
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

class FusedLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {

    @SuppressLint("MissingPermission")
    override val fixes: Flow<Fix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    trySend(location.toFix())
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
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
