package com.byd.tripstats.connections

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The publisher writes its status once a second while the car is on, and the settings screen saves
 * a config it read when the screen was opened. These tests pin which of the two owns which field.
 *
 * The preferences files are the real ones, so each test snapshots them first and puts them back
 * afterwards. Running this on a car must not cost the owner their broker settings.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionStatusOwnershipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Both stores keep PREFS_NAME private. Naming the files here is the price of testing them
    // from outside; a rename that misses this line shows up as a test that stops asserting.
    private val mqttPrefs = "mqtt_connections"
    private val abrpPrefs = "abrp_connections"

    private lateinit var savedMqtt: Map<String, Any?>
    private lateinit var savedAbrp: Map<String, Any?>

    @Before
    fun setUp() {
        savedMqtt = snapshot(mqttPrefs)
        savedAbrp = snapshot(abrpPrefs)
        clear(mqttPrefs)
        clear(abrpPrefs)
    }

    @After
    fun tearDown() {
        restore(mqttPrefs, savedMqtt)
        restore(abrpPrefs, savedAbrp)
    }

    @Test
    fun saving_settings_keeps_the_publish_time_the_publisher_has_moved_on_to() {
        MqttConnectionStore.save(context, MqttConnectionConfig(enabled = true, brokerUrl = "old.example"))
        MqttConnectionStore.updateStatus(context, "MQTT publish ok", 5_000L)

        // The settings screen reads once, when it opens, and holds that config while the user types.
        val screenOpened = MqttConnectionStore.load(context)
        MqttConnectionStore.updateStatus(context, "MQTT publish ok", 9_000L)
        MqttConnectionStore.save(context, screenOpened.copy(brokerUrl = "new.example"))

        val stored = MqttConnectionStore.load(context)
        assertEquals("new.example", stored.brokerUrl)
        assertEquals(9_000L, stored.lastPublishAtMs)
        assertEquals("MQTT publish ok", stored.lastStatus)
    }

    @Test
    fun a_status_write_leaves_every_setting_the_user_chose_alone() {
        val config = MqttConnectionConfig(
            enabled = true,
            brokerUrl = "broker.example",
            brokerPort = 8883,
            username = "car",
            password = "secret",
            friendlyName = "mybyd",
            publishIntervalSeconds = 5,
            useTls = true,
            useWebSocket = true,
            webSocketPath = "/ws",
        )
        MqttConnectionStore.save(context, config)

        MqttConnectionStore.updateStatus(context, "MQTT publish failed")

        assertEquals(config.copy(lastStatus = "MQTT publish failed"), MqttConnectionStore.load(context))
    }

    @Test
    fun a_failed_publish_does_not_reset_the_interval_the_next_tick_measures_against() {
        MqttConnectionStore.updateStatus(context, "MQTT publish ok", 9_000L)

        MqttConnectionStore.updateStatus(context, "MQTT publish failed")

        val stored = MqttConnectionStore.load(context)
        assertEquals(9_000L, stored.lastPublishAtMs)
        assertEquals("MQTT publish failed", stored.lastStatus)
    }

    @Test
    fun saving_abrp_settings_keeps_the_upload_time_the_uploader_has_moved_on_to() {
        AbrpConnectionStore.save(context, AbrpConnectionConfig(enabled = true, userToken = "old-token"))
        AbrpConnectionStore.updateStatus(context, "ABRP upload ok", 5_000L)

        val screenOpened = AbrpConnectionStore.load(context)
        AbrpConnectionStore.updateStatus(context, "ABRP upload ok", 9_000L)
        AbrpConnectionStore.save(context, screenOpened.copy(userToken = "new-token"))

        val stored = AbrpConnectionStore.load(context)
        assertEquals("new-token", stored.userToken)
        assertEquals(9_000L, stored.lastUploadAtMs)
    }

    private fun snapshot(name: String): Map<String, Any?> =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()

    private fun clear(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun restore(name: String, saved: Map<String, Any?>) {
        val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        edit.commit()
    }
}
