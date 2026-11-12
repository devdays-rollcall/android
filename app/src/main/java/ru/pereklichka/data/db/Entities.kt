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

/** Колонки отметки свежести (`Stamped`), встраиваются в строки событий и состояний. */
data class StampColumns(
    val ageAtReceiptMs: Long,
    val receivedAtElapsedMs: Long,
    val receivedBootId: String,
    val receivedAtWallMs: Long,
)

/**
 * Строка события любого автора. Пара (author, seq) уникальна, поэтому база не даст записать в поток автора
 * два события с одним номером. [ignored] стоит у события незнакомого типа, чтобы такие строки было видно в базе.
 * Правила отсеивают их по `EventType.UNKNOWN` после чтения.
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
 * Строка точки трека любого автора. [uploaded] ставит `UploadWorker` после ответа 202, [rejected] стоит у точки,
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

/** Строка последнего состояния участника, одна на участника. */
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

/**
 * Группа, которую стёрли с телефона, пока часть её трека ещё не ушла на сервер. По [token] и [senderId] `UploadWorker`
 * отправляет её точки из [OutboxPointEntity], а когда они кончаются, удаляет и строку группы.
 */
@Entity(tableName = "outbox_group")
data class OutboxGroupEntity(
    @PrimaryKey val tag: String,
    val token: String,
    val senderId: String,
)

/**
 * Неотправленная точка стёртой группы [groupTag]: те же колонки, что у [LocationPointEntity], без пометок. Строку
 * удаляет `UploadWorker`, когда сервер принял точку или её нельзя отправить. В обмен с соседями эти точки не идут.
 */
@Entity(tableName = "outbox_point", indices = [Index(value = ["groupTag"])])
data class OutboxPointEntity(
    @PrimaryKey val id: String,
    val groupTag: String,
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
)
