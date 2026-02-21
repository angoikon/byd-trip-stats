package com.byd.tripstats.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    
    @TypeConverter
    fun fromMapToString(map: Map<String, Double>): String {
        return json.encodeToString(map)
    }
    
    @TypeConverter
    fun fromStringToMap(value: String): Map<String, Double> {
        return json.decodeFromString(value)
    }
}
