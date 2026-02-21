package com.byd.sealstats.data.mqtt

import android.util.Log
import com.byd.sealstats.data.model.VehicleTelemetry
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
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
    
    private var mqttClient: Mqtt5AsyncClient? = null
    private var isConnected = false

    fun connect(): Flow<ConnectionState> = callbackFlow {
        Log.d(TAG, "=== CONNECT CALLED ===")
        Log.d(TAG, "Broker: $brokerUrl:$brokerPort")

        trySend(ConnectionState.Connecting)

        try {
            Log.d(TAG, "Creating MQTT client...")

            val clientBuilder = MqttClient.builder()
                .useMqttVersion5()
                .identifier("BydSealStats_${System.currentTimeMillis()}")
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
                        Log.i(TAG, "Connected to MQTT broker")
                        trySend(ConnectionState.Connected)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            trySend(ConnectionState.Error(e.message ?: "Unknown error"))
        }

        awaitClose {
            disconnect()
        }
    }

    fun subscribeToTelemetry(): Flow<Result<VehicleTelemetry>> = callbackFlow {
        val client = mqttClient ?: run {
            trySend(Result.failure(Exception("MQTT client not initialized")))
            close()
            return@callbackFlow
        }
        
        client.subscribeWith()
            .topicFilter(topic)
            .callback { publish: Mqtt5Publish ->
                try {
                    val payload = String(publish.payloadAsBytes)
                    Log.d(TAG, "=== MQTT MESSAGE RECEIVED ===")
                    Log.d(TAG, "Topic: ${publish.topic}")
                    Log.d(TAG, "Payload: $payload")

                    val telemetry = json.decodeFromString<VehicleTelemetry>(payload)
                    Log.d(TAG, "Successfully parsed telemetry!")
                    trySend(Result.success(telemetry))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse telemetry", e)
                    Log.e(TAG, "Error details: ${e.message}")
                    trySend(Result.failure(e))
                }
            }
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "Subscription failed", throwable)
                    trySend(Result.failure(throwable))
                } else {
                    Log.i(TAG, "Subscribed to topic: $topic")
                }
            }
        
        awaitClose {
            try {
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
