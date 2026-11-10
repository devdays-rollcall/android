package ru.pereklichka.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Активная группа, одна строка на всю базу. Пишет её только `GroupRepository`. */
@Entity(tableName = "group_info")
data class GroupInfoEntity(
    @PrimaryKey val tag: String,
    val token: String,
    val name: String,
    val leaderId: String,
    val myMemberId: String,
    val createdAtWallMs: Long,
)

/** Отметка свежести записи (`Stamped`), встраивается в строки событий и состояний. */
data class StampColumns(
    val ageAtReceiptMs: Long,
    val receivedAtElapsedMs: Long,
    val receivedBootId: String,
    val receivedAtWallMs: Long,
)

/**
 * Событие любого автора. Пара (author, seq) уникальна: на ней держится непрерывный префикс.
 * [ignored] стоит у события незнакомого типа: оно хранится ради префикса, но правила его не учитывают.
 */
@Entity(tableName = "control_event", indices = [Index(value = ["author", "seq"], unique = true)])
data class ControlEventEntity(
    @PrimaryKey val id: String,
    val author: String,
    val seq: Long,
    val type: String,
    val target: String?,
    val refs: List<String>,
    val name: String?,
    val durationMs: Long?,
    val resolveKind: String?,
    val createdAtWallMs: Long,
    val ignored: Boolean,
    @Embedded val stamp: StampColumns,
)

/**
 * Точка трека любого автора. [uploaded] ставит `UploadWorker` после ответа 202, [rejected] стоит у точки,
 * которую нельзя отправлять на сервер. Других изменений у точки после записи не бывает.
 *
 * Широта и долгота допускают NULL: SQLite хранит NaN как NULL, а чужую точку с NaN всё равно нужно сохранить,
 * иначе поток её автора встанет. Маппер возвращает NULL обратно как NaN.
 */
@Entity(
    tableName = "location_point",
    indices = [Index(value = ["author", "seq"], unique = true), Index(value = ["uploaded"])],
)
data class LocationPointEntity(
    @PrimaryKey val id: String,
    val author: String,
    val seq: Long,
    val latitude: Double?,
    val longitude: Double?,
    val recordedAtMs: Long,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val batteryPercent: Int?,
    val uploaded: Boolean = false,
    val rejected: Boolean = false,
)

/** Последнее состояние участника, одна строка на участника. */
@Entity(tableName = "member_state")
data class MemberStateEntity(
    @PrimaryKey val memberId: String,
    val seq: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val fixAgeMs: Long?,
    val batteryPercent: Int?,
    val heardAgoMs: Map<String, Long>,
    @Embedded val stamp: StampColumns,
)

/** Строка запроса «последний номер по каждому автору». */
data class AuthorMaxSeq(val author: String, val maxSeq: Long)
