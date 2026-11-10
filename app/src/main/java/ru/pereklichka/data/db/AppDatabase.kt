package ru.pereklichka.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * База Room с данными одной активной группы: группа, события, точки трека и состояния участников.
 * Создание новой группы или вступление в другую очищает все таблицы ([ru.pereklichka.data.GroupRepository]).
 * Схема выгружается в `app/schemas`, чтобы её изменения было видно в ревью.
 */
@Database(
    entities = [GroupInfoEntity::class, ControlEventEntity::class, LocationPointEntity::class, MemberStateEntity::class],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun controlEventDao(): ControlEventDao
    abstract fun locationDao(): LocationDao
    abstract fun memberStateDao(): MemberStateDao

    companion object {
        const val NAME = "pereklichka.db"
    }
}
