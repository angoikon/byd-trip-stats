package com.byd.tripstats.data.mqtt

import android.util.Log
import com.byd.tripstats.data.model.VehicleTelemetry
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

class MqttClientManager(
    private val brokerUrl: String,
    private val brokerPort: Int,
    private val username: String?,
    private val password: String?,
    private val topic: String
) {
    private val TAG = "MqttClientManager"
    private val json = Json { ignoreUnknownKeys = true }
    
    // CRITICAL FIX: Use MQTT 3 instead of MQTT 5
    private var mqttClient: Mqtt3AsyncClient? = null
    private var isConnected = false

    fun connect(): Flow<ConnectionState> = callbackFlow {
        Log.d(TAG, "=== CONNECT CALLED ===")
        Log.d(TAG, "Broker: $brokerUrl:$brokerPort")

        trySend(ConnectionState.Connecting)

        try {
            Log.d(TAG, "Creating MQTT 3.1.1 client...")

            val clientBuilder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("BydTripStats_${System.currentTimeMillis()}")
                .serverHost(brokerUrl)
                .serverPort(brokerPort)
                .automaticReconnect()
                    .initialDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                    .maxDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                    .applyAutomaticReconnect()

            Log.d(TAG, "Adding SSL config...")

            if (brokerPort == 8883) {
                clientBuilder.sslWithDefaultConfig()
            }

            Log.d(TAG, "Adding auth...")

            // Add authentication if provided
            if (username != null && password != null) {
                clientBuilder.simpleAuth()
                    .username(username)
                    .password(password.toByteArray())
                    .applySimpleAuth()
            }

            Log.d(TAG, "Building client...")
            mqttClient = clientBuilder.buildAsync()

            Log.d(TAG, "Client built, attempting connection...")

            mqttClient?.connectWith()
                ?.send()
                ?.whenComplete { _, throwable ->
                    Log.d(TAG, "=== Connection completed callback ===")
                    if (throwable != null) {
                        Log.e(TAG, "Failed to connect", throwable)
                        Log.e(TAG, "Error type: ${throwable.javaClass.simpleName}")
                        Log.e(TAG, "Error message: ${throwable.message}")
                        trySend(ConnectionState.Error(throwable.message ?: "Connection failed"))
                    } else {
                        isConnected = true
                        Log.i(TAG, "✅ Connected to MQTT broker successfully!")
                        Log.i(TAG, "   Broker: $brokerUrl:$brokerPort")
                        Log.i(TAG, "   Topic: $topic")
                        
                        // CRITICAL: Emit Connected state
                        trySend(ConnectionState.Connected)
                        
                        Log.d(TAG, "Connection flow staying open for state updates...")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            trySend(ConnectionState.Error(e.message ?: "Unknown error"))
        }

        awaitClose {
            Log.d(TAG, "Connection flow closed, disconnecting client...")
            disconnect()
        }
    }

    fun subscribeToTelemetry(): Flow<Result<VehicleTelemetry>> = callbackFlow {
        Log.d(TAG, "=== subscribeToTelemetry CALLED ===")
        
        val client = mqttClient ?: run {
            Log.e(TAG, "❌ MQTT client not initialized!")
            trySend(Result.failure(Exception("MQTT client not initialized")))
            close()
            return@callbackFlow
        }
        
        if (!isConnected) {
            Log.e(TAG, "❌ MQTT client not connected!")
            trySend(Result.failure(Exception("MQTT client not connected")))
            close()
            return@callbackFlow
        }
        
        Log.d(TAG, "Subscribing to topic: $topic")
        
        client.subscribeWith()
            .topicFilter(topic)
            .callback { publish: Mqtt3Publish ->  // ← CHANGED FROM Mqtt5Publish
                try {
                    val payload = String(publish.payloadAsBytes)
                    Log.d(TAG, "═══════════════════════════════════════════════════════")
                    Log.d(TAG, "🔔🔔🔔 MQTT MESSAGE RECEIVED! 🔔🔔🔔")
                    Log.d(TAG, "   Topic: ${publish.topic}")
                    Log.d(TAG, "   Payload size: ${payload.length} bytes")
                    Log.d(TAG, "   Payload preview: ${payload.take(100)}...")
                    Log.d(TAG, "═══════════════════════════════════════════════════════")

                    val telemetry = json.decodeFromString<VehicleTelemetry>(payload)
                    Log.d(TAG, "✅ Successfully parsed telemetry!")
                    Log.d(TAG, "   SoC: ${telemetry.soc}%")
                    Log.d(TAG, "   Speed: ${telemetry.speed} km/h")
                    Log.d(TAG, "   Gear: ${telemetry.gear}")
                    
                    trySend(Result.success(telemetry))
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse telemetry", e)
                    Log.e(TAG, "Error details: ${e.message}")
                    trySend(Result.failure(e))
                }
            }
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "❌ Subscription failed", throwable)
                    trySend(Result.failure(throwable))
                } else {
                    Log.i(TAG, "✅ Successfully subscribed to topic: $topic")
                    Log.i(TAG, "   Waiting for messages from Electro...")
                }
            }
        
        awaitClose {
            try {
                Log.d(TAG, "Unsubscribing from topic: $topic")
                client.unsubscribeWith()
                    .topicFilter(topic)
                    .send()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unsubscribe", e)
            }
        }
    }
    
    fun disconnect() {
        mqttClient?.let { client ->
            try {
                client.disconnect().whenComplete { _, _ ->
                    isConnected = false
                    Log.i(TAG, "Disconnected from MQTT broker")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            }
        }
        mqttClient = null
    }
    
    fun isConnected(): Boolean = isConnected
    
    sealed class ConnectionState {
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
        object Disconnected : ConnectionState()
    }
}