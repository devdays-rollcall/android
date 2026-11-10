package ru.pereklichka.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

/** Списки ссылок и карта `heardAgoMs` хранятся в колонке строкой JSON: по ним никогда не ищут. */
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
