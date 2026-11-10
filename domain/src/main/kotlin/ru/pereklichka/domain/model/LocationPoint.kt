package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/**
 * Точка трека для сервера.
 *
 * Создаёт её хранилище автора в `MeshStore.appendOwnLocation` из фиксации, которую пропустил
 * `TrackThinner`. [seq] идёт подряд с единицы в потоке точек автора, отдельно от номеров событий.
 * Поля повторяют `LocationData` из `docs/server/openapi.yaml`; необязательные заполнены, только
 * если измерены, нулями их не подменяют. [recordedAtMs] это `Location.time` по часам автора, оно
 * уходит в `recorded_at` и в расчётах на телефоне не участвует.
 */
@Serializable
data class LocationPoint(
    val id: EventId,
    val author: MemberId,
    val seq: Long,
    val latitude: Double,
    val longitude: Double,
    val recordedAtMs: Long,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val batteryPercent: Int? = null,
) {
    /**
     * Укладываются ли значения в диапазоны `LocationData` из `openapi.yaml`, все числа конечные.
     *
     * Сервер отклоняет пачку целиком из-за одной неверной точки, поэтому хранилище помечает такие точки
     * `rejected`, а `UploadWorker` проверяет пачку ещё раз перед отправкой. Из префикса автора точку
     * при этом не убирают: иначе его поток застрянет.
     */
    fun hasValidValues(): Boolean =
        // NaN и бесконечность не попадают ни в один замкнутый диапазон, поэтому isFinite нужен только там,
        // где диапазон открыт сверху.
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            (accuracyM == null || accuracyM.isFinite() && accuracyM >= 0.0) &&
            (altitudeM == null || altitudeM.isFinite()) &&
            (speedMps == null || speedMps.isFinite() && speedMps >= 0.0) &&
            (bearingDeg == null || bearingDeg >= 0.0 && bearingDeg < 360.0) &&
            (batteryPercent == null || batteryPercent in 0..100)
}
