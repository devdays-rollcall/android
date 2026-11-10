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

/** Запросы к точкам трека. Выборку неотправленных и пометки использует `UploadWorker`, остальное `RoomMeshStore`. */
@Dao
interface LocationDao {
    @Insert
    suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT author, MAX(seq) AS maxSeq FROM location_point GROUP BY author")
    suspend fun maxSeqByAuthor(): List<AuthorMaxSeq>

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
    fun observeAll(): Flow<List<MemberStateEntity>>

    @Query("SELECT COUNT(*) FROM member_state")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM member_state")
    suspend fun deleteAll()
}
