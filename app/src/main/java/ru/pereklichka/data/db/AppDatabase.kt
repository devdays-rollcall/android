package ru.pereklichka.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * База Room с данными одной активной группы (группа, события, точки трека, состояния участников) и очередью
 * неотправленного трека стёртых групп (`outbox_group`, `outbox_point`). Создание новой группы, вступление в другую
 * и «Новая группа» переносят неотправленные точки в очередь и очищают таблицы активной группы
 * ([ru.pereklichka.data.GroupRepository]). Схема выгружается в `app/schemas`, чтобы её изменения было видно в ревью.
 */
@Database(
    entities = [
        GroupInfoEntity::class,
        ControlEventEntity::class,
        LocationPointEntity::class,
        MemberStateEntity::class,
        OutboxGroupEntity::class,
        OutboxPointEntity::class,
    ],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun controlEventDao(): ControlEventDao
    abstract fun locationDao(): LocationDao
    abstract fun memberStateDao(): MemberStateDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "pereklichka.db"
    }
}
