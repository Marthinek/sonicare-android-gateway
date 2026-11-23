package cz.marthas.sonicareble

import android.content.Context
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import org.json.JSONObject
import java.util.UUID

data class MqttConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val baseTopic: String = "sonicare/hx7410",
    val tls: Boolean = false
)

class MqttManager(
    context: Context,
    private val logger: (String) -> Unit
) {
    private val prefs =
        context.getSharedPreferences("mqtt_config", Context.MODE_PRIVATE)

    @Volatile
    private var client: Mqtt3AsyncClient? = null

    fun loadConfig(): MqttConfig {
        return MqttConfig(
            enabled = prefs.getBoolean("enabled", false),
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 1883),
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            baseTopic = prefs.getString("baseTopic", "sonicare/hx7410")
                ?: "sonicare/hx7410",
            tls = prefs.getBoolean("tls", false)
        )
    }

    fun saveConfig(config: MqttConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("baseTopic", config.baseTopic)
            .putBoolean("tls", config.tls)
            .apply()
    }

    fun connect(config: MqttConfig, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (config.host.isBlank()) {
            onResult(false, "MQTT broker není nastaven")
            return
        }

        disconnect()

        try {
            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(
                    "SonicareBleDiagnostic-" +
                        UUID.randomUUID().toString().take(8)
                )
                .serverHost(config.host)
                .serverPort(config.port)

            if (config.tls) {
                builder.useSslWithDefaultConfig()
            }

            val mqttClient = builder.buildAsync()
            client = mqttClient

            logger(
                "MQTT: connecting to ${config.host}:${config.port}" +
                    if (config.tls) " TLS" else ""
            )

            val connectFuture =
                if (config.username.isNotBlank()) {
                    mqttClient.connectWith()
                        .simpleAuth()
                        .username(config.username)
                        .password(config.password.toByteArray(Charsets.UTF_8))
                        .applySimpleAuth()
                        .send()
                } else {
                    mqttClient.connect()
                }

            connectFuture.whenComplete { _, throwable ->
                if (throwable != null) {
                    logger("MQTT: connection FAILED: ${throwable.message ?: throwable.javaClass.simpleName}")
                    onResult(false, throwable.message ?: throwable.javaClass.simpleName)
                } else {
                    logger("MQTT: connected OK")
                    publishHomeAssistantDiscovery(config)
                    onResult(true, "Připojeno")
                }
            }
        } catch (e: Exception) {
            logger("MQTT: connect exception: ${e.javaClass.simpleName}: ${e.message}")
            onResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun testConnection(config: MqttConfig, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        connect(config) { connected, message ->
            if (!connected) {
                onResult(false, message)
                return@connect
            }

            val topic = config.baseTopic.trimEnd('/') + "/test"
            val payload = "hello from SonicareBleDiagnostic"

            publish(topic, payload) { success, publishMessage ->
                if (success) {
                    logger("MQTT: TEST published -> $topic")
                    onResult(true, "Test MQTT OK: $topic")
                } else {
                    onResult(false, "MQTT publish failed: $publishMessage")
                }
            }
        }
    }

    fun publish(
        topic: String,
        payload: String,
        retained: Boolean = false,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val mqttClient = client

        if (mqttClient == null || !mqttClient.state.isConnected) {
            onResult(false, "MQTT není připojeno")
            return
        }

        try {
            mqttClient.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retained)
                .payload(payload.toByteArray(Charsets.UTF_8))
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        logger(
                            "MQTT: publish FAILED -> $topic: " +
                                (throwable.message ?: throwable.javaClass.simpleName)
                        )
                        onResult(false, throwable.message ?: throwable.javaClass.simpleName)
                    } else {
                        logger(
                            "MQTT: published -> $topic" +
                                if (retained) " (retained)" else ""
                        )
                        onResult(true, "OK")
                    }
                }
        } catch (e: Exception) {
            logger("MQTT: publish exception -> $topic: ${e.message}")
            onResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Publishes Home Assistant MQTT Discovery configuration.
     *
     * Discovery messages are retained so Home Assistant can recreate the
     * Sonicare device after a restart without the phone having to publish
     * the live telemetry again first.
     */
    fun publishHomeAssistantDiscovery(config: MqttConfig = loadConfig()) {
        if (!config.enabled || config.baseTopic.isBlank()) {
            return
        }

        if (client?.state?.isConnected != true) {
            logger("MQTT: Home Assistant discovery skipped; broker is not connected")
            return
        }

        val deviceId = "sonicare_hx7410"
        val device = JSONObject()
            .put("identifiers", org.json.JSONArray().put(deviceId))
            .put("name", "Philips Sonicare HX7410")
            .put("manufacturer", "Philips")
            .put("model", "HX7410/02")

        fun publishSensor(
            objectId: String,
            name: String,
            stateTopic: String,
            valueTemplate: String,
            unit: String? = null,
            deviceClass: String? = null,
            stateClass: String? = "measurement"
        ) {
            val discoveryTopic = "homeassistant/sensor/$deviceId/$objectId/config"
            val payload = JSONObject()
                .put("name", name)
                .put("unique_id", "$deviceId" + "_" + objectId)
                .put("state_topic", stateTopic)
                .put("value_template", valueTemplate)
                .put("device", device)

            if (unit != null) {
                payload.put("unit_of_measurement", unit)
            }
            if (deviceClass != null) {
                payload.put("device_class", deviceClass)
            }
            if (stateClass != null) {
                payload.put("state_class", stateClass)
            }

            publish(
                topic = discoveryTopic,
                payload = payload.toString(),
                retained = true
            )
        }

        val base = config.baseTopic.trimEnd('/')

        publishSensor(
            objectId = "battery_percent",
            name = "Battery",
            stateTopic = "$base/battery",
            valueTemplate = "{{ value_json.BatteryPercent }}",
            unit = "%",
            deviceClass = "battery"
        )

        publishSensor(
            objectId = "battery_voltage",
            name = "Battery voltage",
            stateTopic = "$base/battery",
            valueTemplate = "{{ value_json.BatteryMV }}",
            unit = "mV",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "routine_session",
            name = "Routine session",
            stateTopic = "$base/routine",
            valueTemplate = "{{ value_json.SessionID }}",
            stateClass = null
        )

        publishSensor(
            objectId = "routine_duration",
            name = "Routine duration",
            stateTopic = "$base/routine",
            valueTemplate = "{{ value_json.Duration }}",
            unit = "s",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "routine_length",
            name = "Routine length",
            stateTopic = "$base/routine",
            valueTemplate = "{{ value_json.Length }}",
            unit = "s",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "routine_intensity",
            name = "Routine intensity",
            stateTopic = "$base/routine",
            valueTemplate = "{{ value_json.Intensity }}",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "brushhead_usage",
            name = "Brush head usage",
            stateTopic = "$base/brushhead",
            valueTemplate = "{{ value_json.LifetimeUsage }}",
            unit = "s",
            stateClass = "total_increasing"
        )

        publishSensor(
            objectId = "brushhead_limit",
            name = "Brush head lifetime limit",
            stateTopic = "$base/brushhead",
            valueTemplate = "{{ value_json.LifetimeLimit }}",
            unit = "s",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "brushhead_usage_percent",
            name = "Brush head usage %",
            stateTopic = "$base/brushhead",
            valueTemplate = "{{ ((value_json.LifetimeUsage | float(0)) / (value_json.LifetimeLimit | float(1)) * 100) | round(1) }}",
            unit = "%",
            stateClass = "measurement"
        )

        publishSensor(
            objectId = "handle_state",
            name = "Handle state",
            stateTopic = "$base/sonicare",
            valueTemplate = "{{ value_json.HandleState }}",
            stateClass = null
        )

        publishSensor(
            objectId = "physical_modes",
            name = "Physical modes",
            stateTopic = "$base/sonicare",
            valueTemplate = "{{ value_json.PhysModes }}",
            stateClass = null
        )

        publishSensor(
            objectId = "handle_time",
            name = "Handle time",
            stateTopic = "$base/sonicare",
            valueTemplate = "{{ value_json.HandleTime }}",
            stateClass = null
        )

        logger("MQTT: Home Assistant discovery published for Philips Sonicare HX7410")
    }

    fun publishTelemetry(port: String, json: String) {
        val config = loadConfig()

        if (!config.enabled || config.baseTopic.isBlank()) {
            return
        }

        if (client?.state?.isConnected != true) {
            logger("MQTT: telemetry skipped; broker is not connected")
            return
        }

        val topic = config.baseTopic.trimEnd('/') + "/" +
            sanitizeTopicPart(port)

        publish(topic, json)
    }

    fun isConnected(): Boolean {
        return client?.state?.isConnected == true
    }

    fun disconnect() {
        val oldClient = client ?: return
        client = null

        try {
            if (oldClient.state.isConnected) {
                oldClient.disconnect()
            }
        } catch (e: Exception) {
            logger("MQTT: disconnect exception: ${e.message}")
        }
    }

    private fun sanitizeTopicPart(value: String): String {
        return value
            .trim()
            .replace("/", "_")
            .replace("#", "_")
            .replace("+", "_")
            .ifBlank { "unknown" }
    }
}