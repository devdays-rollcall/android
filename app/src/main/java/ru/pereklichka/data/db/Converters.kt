package ru.pereklichka.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

/** Переводит списки ссылок и карту `heardAgoMs` в строку JSON и обратно. По этим колонкам никогда не ищут, отдельные таблицы им не нужны. */
class Converters {

    @TypeConverter
    fun stringsToJson(values: List<String>): String = Json.encodeToString(values)

    @TypeConverter
    fun jsonToStrings(json: String): List<String> = Json.decodeFromString(json)

    @TypeConverter
    fun heardAgoToJson(values: Map<String, Long>): String = Json.encodeToString(values)

    @TypeConverter
    fun jsonToHeardAgo(json: String): Map<String, Long> = Json.decodeFromString(json)
}
