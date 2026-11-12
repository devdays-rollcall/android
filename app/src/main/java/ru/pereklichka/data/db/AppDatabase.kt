package ru.pereklichka.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * База Room со всеми группами телефона: активной и прошлыми маршрутами. Записи каждой группы (события, точки трека,
 * состояния участников) лежат в общих таблицах с ключом группы `groupKey`. Создание новой группы и вступление
 * в другую переводят прежнюю активную группу в прошлые маршруты, ничего не стирая, а удаляет маршрут только
 * человек со своего экрана ([ru.pereklichka.data.GroupRepository]). Схема выгружается в `app/schemas`, чтобы её
 * изменения было видно в ревью.
 */
@Database(
    entities = [
        GroupInfoEntity::class,
        ControlEventEntity::class,
        LocationPointEntity::class,
        MemberStateEntity::class,
    ],
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
