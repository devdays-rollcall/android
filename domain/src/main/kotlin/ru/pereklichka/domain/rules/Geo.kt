package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.LatLon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Расстояние и направление между точками на сфере. Нужны [GroupCalculator], `TrackThinner` и `LocationSanitizer`. */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Расстояние в метрах по формуле гаверсинусов. */
    fun distanceM(from: LatLon, to: LatLon): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val halfLatDelta = (toLat - fromLat) / 2
        val halfLonDelta = Math.toRadians(to.longitude - from.longitude) / 2
        val haversine = sin(halfLatDelta) * sin(halfLatDelta) +
            cos(fromLat) * cos(toLat) * sin(halfLonDelta) * sin(halfLonDelta)
        return 2 * EARTH_RADIUS_M * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    /** Начальный азимут от [from] на [to] в градусах от севера по часовой стрелке, в полуинтервале [0, 360). */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val lonDelta = Math.toRadians(to.longitude - from.longitude)
        val y = sin(lonDelta) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(lonDelta)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /**
     * Приводит угол к полуинтервалу [0, 360): 360 становится 0, −10 становится 350. Проверка на 360 нужна, потому что
     * сумма с 360 у очень малых отрицательных углов округляется ровно до 360.
     */
    fun normalizeDegrees(degrees: Double): Double {
        val normalized = ((degrees % 360) + 360) % 360
        return if (normalized >= 360.0) 0.0 else normalized
    }
}
