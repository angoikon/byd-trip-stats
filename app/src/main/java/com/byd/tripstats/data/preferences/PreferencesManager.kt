package com.byd.tripstats.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mqtt_settings")

class PreferencesManager(private val context: Context) {
    
    companion object {
        private val BROKER_URL = stringPreferencesKey("broker_url")
        private val BROKER_PORT = intPreferencesKey("broker_port")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val TOPIC = stringPreferencesKey("topic")
    }
    
    data class MqttSettings(
        val brokerUrl: String = "STRING.s1.eu.hivemq.cloud",
        val brokerPort: Int = 8883,
        val username: String = "",
        val password: String = "",
        val topic: String = "electro/telemetry/byd-seal/data"
    )
    
    val mqttSettings: Flow<MqttSettings> = context.dataStore.data.map { preferences ->
        MqttSettings(
            brokerUrl = preferences[BROKER_URL] ?: "STRING.s1.eu.hivemq.cloud",
            brokerPort = preferences[BROKER_PORT] ?: 8883,
            username = preferences[USERNAME] ?: "",
            password = preferences[PASSWORD] ?: "",
            topic = preferences[TOPIC] ?: "electro/telemetry/byd-seal/data"
        )
    }
    
    suspend fun saveMqttSettings(
        brokerUrl: String,
        brokerPort: Int,
        username: String,
        password: String,
        topic: String
    ) {
        context.dataStore.edit { preferences ->
            preferences[BROKER_URL] = brokerUrl
            preferences[BROKER_PORT] = brokerPort
            preferences[USERNAME] = username
            preferences[PASSWORD] = password
            preferences[TOPIC] = topic
        }
    }
    
    suspend fun getMqttSettings(): MqttSettings {
        var settings = MqttSettings()
        context.dataStore.data.collect { preferences ->
            settings = MqttSettings(
                brokerUrl = preferences[BROKER_URL] ?: "STRING.s1.eu.hivemq.cloud",
                brokerPort = preferences[BROKER_PORT] ?: 8883,
                username = preferences[USERNAME] ?: "",
                password = preferences[PASSWORD] ?: "",
                topic = preferences[TOPIC] ?: "electro/telemetry/byd-seal/data"
            )
        }
        return settings
    }
}
