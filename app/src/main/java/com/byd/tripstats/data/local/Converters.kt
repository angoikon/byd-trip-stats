package com.byd.tripstats.data.local

import androidx.room.TypeConverter
import com.byd.tripstats.data.local.entity.LatLng
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Map<String, Double> ───────────────────────────────────────────────────

    @TypeConverter
    fun fromDoubleMap(map: Map<String, Double>): String = json.encodeToString(map)

    @TypeConverter
    fun toDoubleMap(value: String): Map<String, Double> =
        if (value.isBlank()) emptyMap()
        else try { json.decodeFromString(value) } catch (e: Exception) { emptyMap() }

    // ── Map<String, Int> — matrixDistribution ─────────────────────────────────

    @TypeConverter
    fun fromIntMap(map: Map<String, Int>): String = json.encodeToString(map)

    @TypeConverter
    fun toIntMap(value: String): Map<String, Int> =
        if (value.isBlank()) emptyMap()
        else try { json.decodeFromString(value) } catch (e: Exception) { emptyMap() }

    // ── List<LatLng> — compressedRoute ────────────────────────────────────────

    @TypeConverter
    fun fromLatLngList(list: List<LatLng>): String = json.encodeToString(list)

    @TypeConverter
    fun toLatLngList(value: String): List<LatLng> =
        if (value.isBlank()) emptyList()
        else try { json.decodeFromString(value) } catch (e: Exception) { emptyList() }
}