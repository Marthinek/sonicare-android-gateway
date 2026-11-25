package cz.marthas.sonicareble.condor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gateway side of the MQTT contract of the philips_sonicare_ble Home
 * Assistant integration (docs/MQTT_GATEWAY.md, protocol version 1):
 * https://github.com/mtheli/philips_sonicare_ble/blob/condor-link-split/docs/MQTT_GATEWAY.md
 *
 * The BLE side (handshake, framing, acks, default subscriptions, reading
 * every port) stays in the app. This class only receives what happened there
 * and turns it into MQTT messages, and it runs the requests Home Assistant
 * sends on `<base>/cmd`, one at a time, through [Brush].
 *
 * Not thread-safe: call everything from one thread (the BLE handler thread).
 * It has no Android dependencies so it can be unit tested on the JVM.
 */
class CondorMqttGateway(
    private val out: Publisher,
    private val brush: Brush,
    private val timer: Timer,
    private val gatewayName: String,
    private val gatewayVersion: String,
    private val log: (String) -> Unit = {},
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS
) {
    /** MQTT output; the gateway never talks to a client directly. */
    fun interface Publisher {
        fun publish(topic: String, payload: String, qos: Int, retain: Boolean)
    }

    /** The app's Condor request path, used for Home Assistant requests. */
    interface Brush {
        /** True when no request of the app itself is waiting for an answer. */
        fun isIdle(): Boolean

        /**
         * Send one request frame. The next GenericResponse must be handed to
         * [onResponse]. Returns false if the frame could not be sent.
         */
        fun send(frame: ByteArray, description: String): Boolean

        /** The request sent last got no answer in time; forget it. */
        fun abandon()
    }

    fun interface Timer {
        /** Run [action] after [delayMs]; the returned function cancels it. */
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val STATUS_NO_STATUS = 255
        private const val DEDUP_SIZE = 64

        /** `sonicare_gateway/aabbccddeeff` for `AA:BB:CC:DD:EE:FF`. */
        fun baseTopic(address: String): String =
            "sonicare_gateway/" + address.lowercase().filter { it != ':' }
    }

    var base: String? = null
        private set

    private var info = JSONObject()

    // Brush and session state, as published in <base>/link.
    var session: Long = clockSeconds()
        private set
    var connected = false
        private set
    var snapshot = false
        private set
    var sensorMask = 0
        private set

    /** Full properties per port of the current session ("1" to "Battery"). */
    private val state = LinkedHashMap<Pair<String, String>, JSONObject>()
    private val published = LinkedHashSet<Pair<String, String>>()

    // While the app reads every port: what it read, and the change
    // indications that arrived in between.
    private var reading = false
    private val readState = LinkedHashMap<Pair<String, String>, JSONObject>()
    private val readInds = mutableListOf<Pair<Pair<String, String>, JSONObject>>()

    // Requests from Home Assistant.
    private class Request(val json: JSONObject, val key: Pair<String?, Any?>)

    private class Step(
        val frame: ByteArray,
        val description: String,
        /** null: answer the request with this step's status and data. */
        val then: ((status: Int, data: Any?) -> Unit)?
    )

    private val queue = ArrayDeque<Request>()
    private val inFlight = HashSet<Pair<String?, Any?>>()
    private val answers = object : LinkedHashMap<Pair<String?, Any?>, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String?, Any?>, String>?) =
            size > DEDUP_SIZE
    }
    private var current: Request? = null
    private var currentStep: Step? = null
    private var cancelTimeout: (() -> Unit)? = null

    // --- Setup ------------------------------------------------------------

    /**
     * Choose the brush. Returns true if the base topic changed (the MQTT
     * connection then needs a new last will).
     */
    fun attach(address: String, name: String?): Boolean {
        val newBase = baseTopic(address)
        info = JSONObject()
            .put("protocol", PROTOCOL_VERSION)
            .put("gateway", gatewayName)
            .put("gateway_version", gatewayVersion)
            .put("address", address.uppercase())
        if (!name.isNullOrBlank()) info.put("name", name)
        if (newBase == base) return false
        base = newBase
        published.clear()
        return true
    }

    val willTopic: String? get() = base?.let { "$it/status" }

    /**
     * Call after every (re)connect to the broker: publishes link, info and
     * `online`, then the state of every port again (retained messages may
     * have been lost while the gateway was offline).
     */
    fun onMqttConnected() {
        if (base == null) return
        sendLink()
        sendInfo()
        send("status", "online", retain = true)
        if (snapshot) {
            for (key in state.keys) sendState(key)
        }
    }

    // --- Session events from the BLE side ---------------------------------

    /** The Condor handshake is complete (InitializeResponse received). */
    fun sessionUp() {
        session = maxOf(session + 1, clockSeconds())
        connected = true
        snapshot = false
        sensorMask = 0
        state.clear()
        reading = true
        readState.clear()
        readInds.clear()
        sendLink()
    }

    /** One GetProps result of the session's port sweep. */
    fun portRead(product: String, port: String, jsonText: String) {
        if (!reading || port.endsWith(".b")) return
        val props = parseObject(jsonText) ?: return
        if (props.length() == 0) return
        readState[product to port] = props
    }

    /** The app has read every port. Publishes the snapshot. */
    fun snapshotComplete() {
        if (!connected || !reading) return
        reading = false
        for ((key, props) in readState) {
            // Changes that came in while reading are at least as new.
            for ((indKey, delta) in readInds) {
                if (indKey == key) merge(props, delta)
            }
            state[key] = props
            sendState(key)
        }
        readState.clear()
        readInds.clear()
        // Ports of an earlier snapshot that this one does not have.
        val stale = published.filter { it !in state }
        for (key in stale) {
            send("state/${key.first}/${key.second}", "", retain = true)
            published.remove(key)
        }
        snapshot = true
        sendLink()
        pump()
    }

    /** The brush disconnected. */
    fun sessionDown() {
        if (!connected) return
        connected = false
        snapshot = false
        sensorMask = 0
        reading = false
        readState.clear()
        readInds.clear()
        sendLink()
        finishCurrent(JSONObject().put("error", "not_connected"))
        while (queue.isNotEmpty()) {
            answer(queue.removeFirst(), JSONObject().put("error", "not_connected"))
        }
    }

    /**
     * A ChangeIndication body, after the app has acked it to the brush:
     * `<product>/<port> NUL <body>`.
     */
    fun changeIndication(body: ByteArray) {
        val nul = body.indexOf(0)
        if (nul <= 0) return
        val header = String(body, 0, nul, Charsets.US_ASCII)
        val slash = header.indexOf('/')
        if (slash <= 0 || slash == header.length - 1) return
        val product = header.substring(0, slash)
        val port = header.substring(slash + 1)
        val rest = body.copyOfRange(nul + 1, body.size)

        if (port.endsWith(".b")) {
            // Raw body up to the frame length; trailing zeros are data.
            send(
                "ind/$product/$port",
                JSONObject().put("hex", toHex(rest)).toString(),
                qos = 0
            )
            return
        }

        val text = String(rest, Charsets.UTF_8).trimEnd('\u0000').trim()
        val delta = parseObject(text) ?: return
        send("ind/$product/$port", delta.toString())

        val key = product to port
        if (reading) {
            readInds += key to JSONObject(delta.toString())
        } else if (snapshot) {
            val props = state[key] ?: return
            merge(props, delta)
            sendState(key)
        }
    }

    // --- Requests from Home Assistant -------------------------------------

    /** A message on `<base>/cmd`. */
    fun onCommand(payload: String) {
        val json = parseObject(payload)
        if (json == null) {
            log("MQTT GATEWAY: cmd is not a JSON object: ${payload.take(200)}")
            return
        }
        val key = json.opt("client")?.takeIf { it is String } as String? to json.opt("id")
        // QoS 1 may deliver a request twice: run it once, answer again.
        answers[key]?.let {
            send("resp", it)
            return
        }
        if (key in inFlight) return
        inFlight += key
        queue.addLast(Request(json, key))
        pump()
    }

    /**
     * The GenericResponse to the request sent through [Brush.send].
     * [status] is the status byte (255 if there was none), [jsonText] the
     * body after it.
     */
    fun onResponse(status: Int, jsonText: String?) {
        val step = currentStep ?: return
        cancelTimeout?.invoke()
        cancelTimeout = null
        currentStep = null
        val data: Any? = jsonText?.trim()?.takeIf { it.isNotEmpty() }?.let { parseJson(it) }
        val then = step.then
        if (then != null) {
            then(status, data)
        } else {
            finishCurrent(
                JSONObject()
                    .put("status", status)
                    .put("data", data ?: JSONObject.NULL)
            )
        }
    }

    /** Run the next queued request if the brush is free. Call when the app's own request finished. */
    fun pump() {
        while (current == null && queue.isNotEmpty()) {
            // During the port sweep and while the app waits for an answer of
            // its own, requests wait. Without a session they fail right away.
            if (connected && !(snapshot && brush.isIdle())) return
            val req = queue.removeFirst()
            current = req
            start(req)
        }
    }

    private fun start(req: Request) {
        val json = req.json
        val op = json.opt("op")
        val id = json.opt("id")
        if (!(id is Int || id is Long) || op !in setOf("sensor_stream", "get_props", "put_props")) {
            return finishCurrent(badRequest())
        }
        val product = json.opt("product") as? String
        val port = json.opt("port") as? String
        when (op) {
            "sensor_stream" -> {
                val mask = json.opt("mask")
                if (!(mask is Int || mask is Long)) return finishCurrent(badRequest())
            }
            else -> if (product == null || port == null) return finishCurrent(badRequest())
        }
        if (op == "put_props" && json.opt("props") !is JSONObject) {
            return finishCurrent(badRequest())
        }
        if (!connected) {
            return finishCurrent(JSONObject().put("error", "not_connected"))
        }

        when (op) {
            "get_props" -> step(
                CondorProtocol.getPropsReq(product!!, port!!),
                "GATEWAY GetProps $product/$port",
                null
            )

            "put_props" -> step(
                CondorProtocol.putPropsReq(product!!, port!!, json.getJSONObject("props")),
                "GATEWAY PutProps $product/$port",
                null
            )

            "sensor_stream" -> sensorStream((json.opt("mask") as Number).toInt())
        }
    }

    private fun sensorStream(mask: Int) {
        fun done(status: Int) {
            if (status == CondorProtocol.STATUS_OK && mask != sensorMask) {
                sensorMask = mask
                sendLink()
            }
            finishCurrent(JSONObject().put("status", status).put("data", JSONObject.NULL))
        }

        val enable = CondorProtocol.putPropsReq(
            "1", "SensorData", JSONObject().put("Sensors", mask)
        )
        when {
            mask == sensorMask -> done(CondorProtocol.STATUS_OK)
            // Running: only the enable register changes.
            mask != 0 && sensorMask != 0 ->
                step(enable, "GATEWAY PutProps 1/SensorData") { status, _ -> done(status) }
            // Subscribe first so no frame is sent before we listen; the
            // enable write is best effort, the subscribe decides.
            mask != 0 ->
                step(
                    CondorProtocol.subscribeReq("1", "SensorData.b"),
                    "GATEWAY Subscribe 1/SensorData.b"
                ) { subStatus, _ ->
                    if (subStatus != CondorProtocol.STATUS_OK) {
                        done(subStatus)
                    } else {
                        step(enable, "GATEWAY PutProps 1/SensorData") { _, _ ->
                            done(CondorProtocol.STATUS_OK)
                        }
                    }
                }
            // Off: the handle powers its sensors down on its own.
            else ->
                step(
                    CondorProtocol.unsubscribeReq("1", "SensorData.b"),
                    "GATEWAY Unsubscribe 1/SensorData.b"
                ) { _, _ -> done(CondorProtocol.STATUS_OK) }
        }
    }

    private fun step(
        frame: ByteArray,
        description: String,
        then: ((Int, Any?) -> Unit)?
    ) {
        val s = Step(frame, description, then)
        currentStep = s
        if (!brush.send(frame, description)) {
            currentStep = null
            finishCurrent(JSONObject().put("error", "not_connected"))
            return
        }
        cancelTimeout = timer.schedule(requestTimeoutMs) {
            if (currentStep === s) {
                currentStep = null
                cancelTimeout = null
                log("MQTT GATEWAY: no answer to $description within ${requestTimeoutMs} ms")
                brush.abandon()
                finishCurrent(JSONObject().put("error", "timeout"))
            }
        }
    }

    private fun finishCurrent(body: JSONObject) {
        val req = current ?: return
        current = null
        currentStep = null
        cancelTimeout?.invoke()
        cancelTimeout = null
        answer(req, body)
        pump()
    }

    private fun badRequest() = JSONObject().put("error", "bad_request")

    private fun answer(req: Request, body: JSONObject) {
        val resp = JSONObject().put("id", req.json.opt("id") ?: JSONObject.NULL)
        req.key.first?.let { resp.put("client", it) }
        for (k in body.keys()) resp.put(k, body.get(k))
        val payload = resp.toString()
        inFlight -= req.key
        answers[req.key] = payload
        send("resp", payload)
    }

    // --- Publishing ---------------------------------------------------------

    private fun send(sub: String, payload: String, qos: Int = 1, retain: Boolean = false) {
        val b = base ?: return
        out.publish("$b/$sub", payload, qos, retain)
    }

    private fun sendInfo() = send("info", info.toString(), retain = true)

    private fun sendLink() {
        val link = JSONObject()
            .put("connected", connected)
            .put("session", session)
            .put("snapshot", snapshot)
            .put("sensor_mask", sensorMask)
        if (snapshot) {
            val ports = JSONArray()
            for ((p, port) in state.keys) ports.put("$p/$port")
            link.put("ports", ports)
        }
        send("link", link.toString(), retain = true)
    }

    private fun sendState(key: Pair<String, String>) {
        val props = state[key] ?: return
        val body = JSONObject().put("session", session).put("props", props)
        send("state/${key.first}/${key.second}", body.toString(), retain = true)
        published += key
    }

    // --- Helpers --------------------------------------------------------------

    private fun merge(target: JSONObject, delta: JSONObject) {
        for (k in delta.keys()) target.put(k, delta.get(k))
    }

    private fun parseObject(text: String): JSONObject? =
        try {
            val t = text.trim()
            if (t.startsWith("{")) JSONObject(t) else null
        } catch (_: Exception) {
            null
        }

    private fun parseJson(text: String): Any? =
        try {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
