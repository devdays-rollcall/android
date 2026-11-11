package ru.pereklichka.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.pereklichka.domain.model.LocationPoint
import java.time.Instant

/**
 * `EventBatch`: пачка не больше 500 событий (`UploadWorker` шлёт по 200), [senderId] равен `member_id` этого телефона.
 * Пустую пачку телефон шлёт как heartbeat, пока идёт маршрут: по ней сервер знает, что телефон на связи,
 * даже когда новых точек нет.
 */
@Serializable
data class EventBatchDto(
    @SerialName("sender_id") val senderId: String,
    val events: List<EventInputDto>,
)

/** `EventInput`. В [id] лежит UUID точки, он не меняется ни при пересылке, ни при повторной отправке. */
@Serializable
data class EventInputDto(
    val id: String,
    @SerialName("member_id") val memberId: String,
    val type: EventTypeDto,
    /** RFC 3339 с `Z`, например `2026-11-09T12:00:00.120Z`. */
    @SerialName("recorded_at") val recordedAt: String,
    val data: LocationDataDto,
)

/** Контракт сервера допускает единственный тип события; другой отклоняет всю пачку. */
@Serializable
enum class EventTypeDto {
    @SerialName("location")
    LOCATION,
}

/**
 * `LocationData`. Отсутствующие измерения не отправляются вовсе: JSON настроен с `explicitNulls = false`,
 * а подменять их нулями спецификация запрещает.
 */
@Serializable
data class LocationDataDto(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
)

/** `Accepted`: тело ответа 202, сервер принял всю пачку. */
@Serializable
data class AcceptedDto(val accepted: Int)

@Serializable
data class EventOutputDto(
    val id: String,
    @SerialName("member_id") val memberId: String,
    val type: EventTypeDto,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("received_at") val receivedAt: String,
    val data: LocationDataDto,
)

@Serializable
data class EventPageDto(
    val events: List<EventOutputDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class AlertResultDto(val notified: Boolean)

@Serializable
data class ShareTokenDto(val token: String)

/** `ErrorResponse`: тело ответов 400, 401, 413, 422 и 503. */
@Serializable
data class ErrorResponseDto(val error: ErrorDetailDto)

@Serializable
data class ErrorDetailDto(val code: String, val message: String)

/** Точка трека в виде события для сервера. Проверку значений перед отправкой делает `UploadWorker`. */
fun LocationPoint.toEventInput(): EventInputDto = EventInputDto(
    id = id.value,
    memberId = author.value,
    type = EventTypeDto.LOCATION,
    recordedAt = Instant.ofEpochMilli(recordedAtMs).toString(),
    data = LocationDataDto(
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        batteryPercent = batteryPercent,
    ),
)
