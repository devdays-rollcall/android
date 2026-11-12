package ru.pereklichka.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Запросы к таблице активной группы (одна строка). */
@Dao
interface GroupDao {
    @Query("SELECT * FROM group_info LIMIT 1")
    suspend fun get(): GroupInfoEntity?

    @Query("SELECT * FROM group_info LIMIT 1")
    fun observe(): Flow<GroupInfoEntity?>

    @Insert
    suspend fun insert(group: GroupInfoEntity)

    @Query("DELETE FROM group_info")
    suspend fun deleteAll()
}

/** Запросы к событиям для `RoomMeshStore`. */
@Dao
interface ControlEventDao {
    /** Падает на повторном id или паре (author, seq): хранилище проверяет это до вставки. */
    @Insert
    suspend fun insert(event: ControlEventEntity)

    @Query("SELECT author, MAX(seq) AS maxSeq FROM control_event GROUP BY author")
    suspend fun maxSeqByAuthor(): List<AuthorMaxSeq>

    /** null, если у автора нет событий. */
    @Query("SELECT MAX(seq) FROM control_event WHERE author = :author")
    suspend fun maxSeq(author: String): Long?

    @Query("SELECT * FROM control_event WHERE author = :author AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun range(author: String, afterSeq: Long, limit: Int): List<ControlEventEntity>

    @Query("SELECT * FROM control_event WHERE author = :author AND seq = :seq")
    suspend fun find(author: String, seq: Long): ControlEventEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM control_event WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM control_event")
    fun observeAll(): Flow<List<ControlEventEntity>>

    @Query("SELECT COUNT(*) FROM control_event")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM control_event")
    suspend fun deleteAll()
}

/** Запросы к точкам трека для `RoomMeshStore`, `UploadWorker` (неотправленные и пометки) и счётчиков на экранах настроек. */
@Dao
interface LocationDao {
    /** Падает на повторном id или паре (author, seq): хранилище проверяет это до вставки. */
    @Insert
    suspend fun insert(point: LocationPointEntity)

    @Insert
    suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT author, MAX(seq) AS maxSeq FROM location_point GROUP BY author")
    suspend fun maxSeqByAuthor(): List<AuthorMaxSeq>

    /** null, если у автора нет точек. */
    @Query("SELECT MAX(seq) FROM location_point WHERE author = :author")
    suspend fun maxSeq(author: String): Long?

    @Query("SELECT * FROM location_point WHERE author = :author AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun range(author: String, afterSeq: Long, limit: Int): List<LocationPointEntity>

    @Query("SELECT * FROM location_point WHERE author = :author AND seq = :seq")
    suspend fun find(author: String, seq: Long): LocationPointEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM location_point WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** Неотправленные и не отклонённые точки по порядку (автор, номер). */
    @Query("SELECT * FROM location_point WHERE uploaded = 0 AND rejected = 0 ORDER BY author, seq LIMIT :limit")
    suspend fun unsent(limit: Int): List<LocationPointEntity>

    /** null, если такой точки нет. Нужен контрактному тесту хранилища и проверке пометок. */
    @Query("SELECT rejected FROM location_point WHERE id = :id")
    suspend fun isRejected(id: String): Boolean?

    @Query("UPDATE location_point SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("UPDATE location_point SET rejected = 1 WHERE id IN (:ids)")
    suspend fun markRejected(ids: List<String>)

    @Query("SELECT COUNT(*) FROM location_point")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_point WHERE uploaded = 0 AND rejected = 0")
    fun observeUnsentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_point WHERE rejected = 1")
    fun observeRejectedCount(): Flow<Int>

    @Query("DELETE FROM location_point")
    suspend fun deleteAll()
}

/** Запросы к последним состояниям участников. */
@Dao
interface MemberStateDao {
    @Upsert
    suspend fun upsert(state: MemberStateEntity)

    @Query("SELECT * FROM member_state WHERE memberId = :memberId")
    suspend fun get(memberId: String): MemberStateEntity?

    @Query("SELECT * FROM member_state")
    suspend fun getAll(): List<MemberStateEntity>

    @Query("SELECT * FROM member_state")
    fun observeAll(): Flow<List<MemberStateEntity>>

    @Query("SELECT COUNT(*) FROM member_state")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM member_state")
    suspend fun deleteAll()
}

/**
 * Очередь трека стёртых групп для `GroupRepository` (перенос при стирании группы) и `UploadWorker` (отправка).
 */
@Dao
interface OutboxDao {
    /** Переносит в очередь под [groupTag] неотправленные и не отклонённые точки из `location_point`. */
    @Query(
        "INSERT OR IGNORE INTO outbox_point " +
            "(id, groupTag, author, seq, latitude, longitude, recordedAtMs, accuracyM, altitudeM, speedMps, bearingDeg, batteryPercent) " +
            "SELECT id, :groupTag, author, seq, latitude, longitude, recordedAtMs, accuracyM, altitudeM, speedMps, bearingDeg, " +
            "batteryPercent FROM location_point WHERE uploaded = 0 AND rejected = 0",
    )
    suspend fun copyUnsentPoints(groupTag: String)

    @Upsert
    suspend fun upsertGroup(group: OutboxGroupEntity)

    @Query("SELECT * FROM outbox_group")
    suspend fun groups(): List<OutboxGroupEntity>

    @Query("SELECT * FROM outbox_point WHERE groupTag = :groupTag ORDER BY author, seq LIMIT :limit")
    suspend fun points(groupTag: String, limit: Int): List<OutboxPointEntity>

    @Query("DELETE FROM outbox_point WHERE id IN (:ids)")
    suspend fun deletePoints(ids: List<String>)

    /** Удаляет группу [tag] из очереди, если её точек там больше нет. */
    @Query("DELETE FROM outbox_group WHERE tag = :tag AND NOT EXISTS (SELECT 1 FROM outbox_point WHERE groupTag = :tag)")
    suspend fun deleteGroupIfEmpty(tag: String)

    @Query("SELECT EXISTS(SELECT 1 FROM outbox_point WHERE groupTag = :groupTag)")
    suspend fun hasPoints(groupTag: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM outbox_point)")
    suspend fun hasAnyPoints(): Boolean

    @Query("SELECT COUNT(*) FROM outbox_point")
    fun observeCount(): Flow<Int>
}
