package ru.pereklichka.domain.track

import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.rules.Geo
import javax.inject.Inject

/**
 * Чистит свою GPS-фиксацию перед сохранением, потому что сервер отклоняет пачку целиком из-за одной неверной точки.
 *
 * Вызывает `TrackRecorder` для каждой фиксации до [TrackThinner] и `MeshStore.appendOwnLocation`. Что сервер
 * считает допустимым, записано в `LocationPoint.hasValidValues()`, и точка из очищенной фиксации эту проверку
 * проходит. Недопустимое необязательное значение пропадает, нулём его не подменяют: спецификация сервера
 * это запрещает.
 */
class LocationSanitizer @Inject constructor() {

    /** Очищенная фиксация или null, если широта и долгота недопустимы и сохранять нечего. */
    fun sanitize(fix: Fix): Fix? {
        val position = fix.position
        if (position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0) return null
        return fix.copy(
            accuracyM = fix.accuracyM?.takeIf { it.isFinite() && it >= 0.0 },
            altitudeM = fix.altitudeM?.takeIf { it.isFinite() },
            speedMps = fix.speedMps?.takeIf { it.isFinite() && it >= 0.0 },
            bearingDeg = fix.bearingDeg?.takeIf { it.isFinite() }?.let(Geo::normalizeDegrees),
        )
    }
}
