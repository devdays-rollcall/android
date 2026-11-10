package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/** Координаты в градусах WGS 84. */
@Serializable
data class LatLon(val latitude: Double, val longitude: Double)

/**
 * GPS-фиксация в том виде, в каком её отдаёт `LocationSource`.
 *
 * Необязательные поля заполнены, только если Android их измерил (`hasAccuracy()` и так далее).
 * [timeUtcMs] берётся из `Location.time`: это часы телефона, они не монотонны и годятся только
 * для `recorded_at` на сервере. Возраст фиксации считается по [elapsedRealtimeMs]
 * (`Location.elapsedRealtimeNanos` в миллисекундах).
 */
data class Fix(
    val position: LatLon,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val timeUtcMs: Long,
    val elapsedRealtimeMs: Long,
)
