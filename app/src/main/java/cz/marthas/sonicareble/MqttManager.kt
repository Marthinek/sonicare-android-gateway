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
     * Discovery messages are retained so Home Assistant can recreate the
     * Sonicare device after a restart.
     */
    fun publishHomeAssistantDiscovery(config: MqttConfig = loadConfig()) {
        if (!config.enabled || config.baseTopic.isBlank()) return
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
            val payload = JSONObject()
                .put("name", name)
                .put("unique_id", "${deviceId}_$objectId")
                .put("state_topic", stateTopic)
                .put("value_template", valueTemplate)
                .put("device", device)

            if (unit != null) payload.put("unit_of_measurement", unit)
            if (deviceClass != null) payload.put("device_class", deviceClass)
            if (stateClass != null) payload.put("state_class", stateClass)

            publish(
                "homeassistant/sensor/$deviceId/$objectId/config",
                payload.toString(),
                retained = true
            )
        }

        val base = config.baseTopic.trimEnd('/')

        publishSensor("battery_percent", "Battery", "$base/battery",
            "{{ value_json.BatteryPercent }}", "%", "battery")
        publishSensor("battery_voltage", "Battery voltage", "$base/battery",
            "{{ value_json.BatteryMV }}", "mV")
        publishSensor("routine_session", "Routine session", "$base/routine",
            "{{ value_json.SessionID }}", stateClass = null)
        publishSensor("routine_duration", "Routine duration", "$base/routine",
            "{{ value_json.Duration }}", "s")
        publishSensor("routine_length", "Routine length", "$base/routine",
            "{{ value_json.Length }}", "s")
        publishSensor("routine_intensity", "Routine intensity", "$base/routine",
            "{{ value_json.Intensity }}")
        publishSensor("brushhead_usage", "Brush head usage", "$base/brushhead",
            "{{ value_json.LifetimeUsage }}", "s", stateClass = "total_increasing")
        publishSensor("brushhead_limit", "Brush head lifetime limit", "$base/brushhead",
            "{{ value_json.LifetimeLimit }}", "s")
        publishSensor("brushhead_usage_percent", "Brush head usage %", "$base/brushhead",
            "{{ ((value_json.LifetimeUsage | float(0)) / (value_json.LifetimeLimit | float(1)) * 100) | round(1) }}", "%")
        publishSensor("handle_state", "Handle state", "$base/sonicare",
            "{{ value_json.HandleState }}", stateClass = null)
        publishSensor("physical_modes", "Physical modes", "$base/sonicare",
            "{{ value_json.PhysModes }}", stateClass = null)
        publishSensor("handle_time", "Handle time", "$base/sonicare",
            "{{ value_json.HandleTime }}", stateClass = null)

        logger("MQTT: Home Assistant discovery published for Philips Sonicare HX7410")
    }

    fun publish(topic: String, payload: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val mqttClient = client

        if (mqttClient == null || !mqttClient.state.isConnected) {
            onResult(false, "MQTT není připojeno")
            return
        }

        try {
            mqttClient.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
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
                        logger("MQTT: published -> $topic")
                        onResult(true, "OK")
                    }
                }
        } catch (e: Exception) {
            logger("MQTT: publish exception -> $topic: ${e.message}")
            onResult(false, e.message ?: e.javaClass.simpleName)
        }
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