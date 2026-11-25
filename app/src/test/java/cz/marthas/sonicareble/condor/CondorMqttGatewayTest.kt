package cz.marthas.sonicareble.condor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CondorMqttGatewayTest {
    private data class Msg(val topic: String, val payload: String, val qos: Int, val retain: Boolean) {
        val json: JSONObject get() = JSONObject(payload)
    }

    private val msgs = mutableListOf<Msg>()
    private val sent = mutableListOf<ByteArray>()
    private var idle = true
    private var abandoned = 0
    private val timers = mutableListOf<Pair<() -> Unit, BooleanArray>>()
    private var now = 1_800_000_000L
    private lateinit var gw: CondorMqttGateway

    private val base = "sonicare_gateway/aabbccddeeff"

    @Before
    fun setUp() {
        gw = CondorMqttGateway(
            out = { t, p, q, r -> msgs += Msg(t, p, q, r) },
            brush = object : CondorMqttGateway.Brush {
                override fun isIdle() = idle
                override fun send(frame: ByteArray, description: String): Boolean {
                    sent += frame
                    return true
                }

                override fun abandon() {
                    abandoned++
                }
            },
            timer = { _, action ->
                val cancelled = booleanArrayOf(false)
                timers += action to cancelled
                return@CondorMqttGateway { cancelled[0] = true }
            },
            gatewayName = "test",
            gatewayVersion = "1",
            clockSeconds = { now }
        )
        assertTrue(gw.attach("AA:BB:CC:DD:EE:FF", "Philips Sonicare"))
    }

    private fun topics() = msgs.map { it.topic.removePrefix("$base/") }
    private fun last(sub: String) = msgs.last { it.topic == "$base/$sub" }
    private fun fireTimers() {
        val pending = timers.toList()
        timers.clear()
        for ((action, cancelled) in pending) if (!cancelled[0]) action()
    }

    private fun ind(header: String, body: ByteArray): ByteArray =
        header.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + body

    private fun ind(header: String, json: String) =
        ind(header, json.toByteArray() + byteArrayOf(0))

    /** Handshake done, two ports read, snapshot complete. */
    private fun readySession() {
        gw.sessionUp()
        gw.portRead("1", "Battery", """{"BatteryPercent":74,"BatteryMV":3950}""")
        gw.portRead("1", "RoutineStatus", """{"Duration":0,"Length":120}""")
        gw.snapshotComplete()
        msgs.clear()
    }

    private fun frameType(frame: ByteArray) = frame[2].toInt() and 0xFF
    private fun frameText(frame: ByteArray) =
        String(frame, 5, frame.size - 5, Charsets.UTF_8).replace('\u0000', '|')

    @Test
    fun baseTopicFromAddress() {
        assertEquals(base, CondorMqttGateway.baseTopic("AA:BB:CC:DD:EE:FF"))
        assertEquals("$base/status", gw.willTopic)
        assertFalse(gw.attach("aa:bb:cc:dd:ee:ff", null))
    }

    @Test
    fun linkIsPublishedBeforeOnline() {
        gw.onMqttConnected()
        assertEquals(listOf("link", "info", "status"), topics())
        assertTrue(msgs.all { it.retain && it.qos == 1 })
        assertEquals("online", last("status").payload)
        val link = last("link").json
        assertFalse(link.getBoolean("connected"))
        val info = last("info").json
        assertEquals(1, info.getInt("protocol"))
        assertEquals("AA:BB:CC:DD:EE:FF", info.getString("address"))
        assertEquals("Philips Sonicare", info.getString("name"))
    }

    @Test
    fun sessionSnapshot() {
        gw.sessionUp()
        val up = last("link").json
        assertTrue(up.getBoolean("connected"))
        assertFalse(up.getBoolean("snapshot"))
        assertEquals(gw.session, up.getLong("session"))

        gw.portRead("1", "Battery", """{"BatteryPercent":74}""")
        gw.portRead("1", "Empty", "{}")
        gw.portRead("1", "SensorData.b", """{"x":1}""")
        gw.portRead("1", "NotJson", "abc")
        gw.snapshotComplete()

        assertEquals(listOf("link", "state/1/Battery", "link"), topics())
        val state = last("state/1/Battery")
        assertTrue(state.retain)
        assertEquals(gw.session, state.json.getLong("session"))
        assertEquals(74, state.json.getJSONObject("props").getInt("BatteryPercent"))
        val link = last("link").json
        assertTrue(link.getBoolean("snapshot"))
        assertEquals(listOf("1/Battery"), link.getJSONArray("ports").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test
    fun sessionNumbersNeverRepeat() {
        gw.sessionUp()
        val first = gw.session
        gw.sessionDown()
        gw.sessionUp()
        assertEquals(first + 1, gw.session)
        now += 1000
        gw.sessionDown()
        gw.sessionUp()
        assertEquals(now, gw.session)
    }

    @Test
    fun indicationsDuringReadWinOverTheRead() {
        gw.sessionUp()
        gw.portRead("1", "RoutineStatus", """{"Duration":3,"Length":120}""")
        gw.changeIndication(ind("1/RoutineStatus", """{"Duration":5}"""))
        // A state is never published before the snapshot is complete.
        assertEquals(listOf("link", "ind/1/RoutineStatus"), topics())
        gw.snapshotComplete()
        val props = last("state/1/RoutineStatus").json.getJSONObject("props")
        assertEquals(5, props.getInt("Duration"))
        assertEquals(120, props.getInt("Length"))
    }

    @Test
    fun indicationUpdatesState() {
        readySession()
        gw.changeIndication(ind("1/RoutineStatus", """{"Duration":1}"""))
        assertEquals(listOf("ind/1/RoutineStatus", "state/1/RoutineStatus"), topics())
        assertEquals("""{"Duration":1}""", last("ind/1/RoutineStatus").payload)
        assertFalse(last("ind/1/RoutineStatus").retain)
        val props = last("state/1/RoutineStatus").json.getJSONObject("props")
        assertEquals(1, props.getInt("Duration"))
        assertEquals(120, props.getInt("Length"))
    }

    @Test
    fun indicationForUnreadPortHasNoState() {
        readySession()
        gw.changeIndication(ind("1/Other", """{"A":1}"""))
        assertEquals(listOf("ind/1/Other"), topics())
    }

    @Test
    fun binaryIndicationKeepsTrailingZeros() {
        readySession()
        gw.changeIndication(ind("1/SensorData.b", byteArrayOf(1, 0, 7, 0, 0, 0x2c, 1, 0)))
        val m = last("ind/1/SensorData.b")
        assertEquals(0, m.qos)
        assertEquals("01000700002c0100", m.json.getString("hex"))
        assertEquals(listOf("ind/1/SensorData.b"), topics())
    }

    @Test
    fun malformedIndicationsAreDropped() {
        readySession()
        gw.changeIndication(byteArrayOf(1, 2, 3))
        gw.changeIndication(ind("NoSlash", """{"A":1}"""))
        gw.changeIndication(ind("1/Battery", "notjson"))
        assertTrue(msgs.isEmpty())
    }

    @Test
    fun staleStateIsClearedOnTheNextSnapshot() {
        readySession()
        gw.sessionDown()
        gw.sessionUp()
        gw.portRead("1", "Battery", """{"BatteryPercent":70}""")
        msgs.clear()
        gw.snapshotComplete()
        val cleared = msgs.single { it.topic == "$base/state/1/RoutineStatus" }
        assertEquals("", cleared.payload)
        assertTrue(cleared.retain)
    }

    @Test
    fun reconnectRepublishesState() {
        readySession()
        gw.onMqttConnected()
        assertEquals(
            listOf("link", "info", "status", "state/1/Battery", "state/1/RoutineStatus"),
            topics()
        )
    }

    @Test
    fun getProps() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"Battery"}""")
        assertEquals(1, sent.size)
        assertEquals(CondorProtocol.MSG_GET_PROPS, frameType(sent[0]))
        assertEquals("1|Battery|", frameText(sent[0]))
        gw.onResponse(0, """{"BatteryPercent":74}""")
        val resp = last("resp").json
        assertEquals(1, resp.getInt("id"))
        assertEquals("ha", resp.getString("client"))
        assertEquals(0, resp.getInt("status"))
        assertEquals(74, resp.getJSONObject("data").getInt("BatteryPercent"))
    }

    @Test
    fun putPropsPassesStatusAndNullData() {
        readySession()
        gw.onCommand("""{"id":2,"client":"ha","op":"put_props","product":"1","port":"X","props":{"A":1}}""")
        assertEquals(CondorProtocol.MSG_PUT_PROPS, frameType(sent[0]))
        assertEquals("""1|X|{"A":1}""", frameText(sent[0]))
        gw.onResponse(3, "")
        val resp = last("resp").json
        assertEquals(3, resp.getInt("status"))
        assertTrue(resp.isNull("data"))
        assertTrue(resp.has("data"))
    }

    @Test
    fun badRequests() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"nope"}""")
        gw.onCommand("""{"id":2,"client":"ha","op":"get_props","product":"1"}""")
        gw.onCommand("""{"id":3,"client":"ha","op":"sensor_stream"}""")
        gw.onCommand("""{"id":4,"client":"ha","op":"put_props","product":"1","port":"X"}""")
        gw.onCommand("""{"id":"5","client":"ha","op":"get_props","product":"1","port":"X"}""")
        gw.onCommand("not json")
        val resps = msgs.filter { it.topic == "$base/resp" }.map { it.json }
        assertEquals(5, resps.size)
        assertTrue(resps.all { it.getString("error") == "bad_request" })
        assertTrue(sent.isEmpty())
    }

    @Test
    fun notConnected() {
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"Battery"}""")
        assertEquals("not_connected", last("resp").json.getString("error"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun duplicateRequestRunsOnceAndIsAnsweredAgain() {
        readySession()
        val cmd = """{"id":7,"client":"ha","op":"get_props","product":"1","port":"Battery"}"""
        gw.onCommand(cmd)
        gw.onCommand(cmd) // arrives while the first one runs
        assertEquals(1, sent.size)
        gw.onResponse(0, """{"BatteryPercent":74}""")
        gw.onCommand(cmd) // arrives after the answer
        assertEquals(1, sent.size)
        val resps = msgs.filter { it.topic == "$base/resp" }
        assertEquals(2, resps.size)
        assertEquals(resps[0].payload, resps[1].payload)
        // Same id from another client is a different request.
        gw.onCommand(cmd.replace("\"ha\"", "\"other\""))
        assertEquals(2, sent.size)
    }

    @Test
    fun requestsWaitForSnapshotAndIdleBrush() {
        gw.sessionUp()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"Battery"}""")
        assertTrue(sent.isEmpty())
        idle = false
        gw.snapshotComplete()
        assertTrue(sent.isEmpty())
        idle = true
        gw.pump()
        assertEquals(1, sent.size)
    }

    @Test
    fun requestsRunOneAfterAnother() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"A"}""")
        gw.onCommand("""{"id":2,"client":"ha","op":"get_props","product":"1","port":"B"}""")
        assertEquals(1, sent.size)
        gw.onResponse(0, "{}")
        assertEquals(2, sent.size)
        assertEquals("1|B|", frameText(sent[1]))
    }

    @Test
    fun timeout() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"A"}""")
        fireTimers()
        assertEquals("timeout", last("resp").json.getString("error"))
        assertEquals(1, abandoned)
        // A late answer is ignored.
        msgs.clear()
        gw.onResponse(0, "{}")
        assertTrue(msgs.isEmpty())
    }

    @Test
    fun answeredRequestCancelsTimeout() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"A"}""")
        gw.onResponse(0, "{}")
        msgs.clear()
        fireTimers()
        assertTrue(msgs.isEmpty())
        assertEquals(0, abandoned)
    }

    @Test
    fun disconnectAnswersQueuedRequests() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"get_props","product":"1","port":"A"}""")
        gw.onCommand("""{"id":2,"client":"ha","op":"get_props","product":"1","port":"B"}""")
        msgs.clear()
        gw.sessionDown()
        assertEquals("link", topics()[0])
        assertFalse(last("link").json.getBoolean("connected"))
        val resps = msgs.filter { it.topic == "$base/resp" }.map { it.json }
        assertEquals(listOf(1, 2), resps.map { it.getInt("id") })
        assertTrue(resps.all { it.getString("error") == "not_connected" })
        // The cancelled timeout of the running request does nothing.
        msgs.clear()
        fireTimers()
        assertTrue(msgs.isEmpty())
    }

    @Test
    fun sensorStreamOnOffAndIdempotent() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"sensor_stream","mask":3}""")
        assertEquals(CondorProtocol.MSG_SUBSCRIBE, frameType(sent[0]))
        assertEquals("""1|SensorData.b|{"timeout":31536000}""", frameText(sent[0]))
        gw.onResponse(0, "")
        assertEquals(CondorProtocol.MSG_PUT_PROPS, frameType(sent[1]))
        assertEquals("""1|SensorData|{"Sensors":3}""", frameText(sent[1]))
        gw.onResponse(1, "") // enable write is best effort
        assertEquals(0, last("resp").json.getInt("status"))
        assertEquals(3, last("link").json.getInt("sensor_mask"))

        // Same mask again: no traffic to the brush.
        gw.onCommand("""{"id":2,"client":"ha","op":"sensor_stream","mask":3}""")
        assertEquals(2, sent.size)
        assertEquals(2, last("resp").json.getInt("id"))
        assertEquals(0, last("resp").json.getInt("status"))

        // Different mask while running: only the enable register.
        gw.onCommand("""{"id":3,"client":"ha","op":"sensor_stream","mask":1}""")
        assertEquals(CondorProtocol.MSG_PUT_PROPS, frameType(sent[2]))
        gw.onResponse(0, "")
        assertEquals(1, last("link").json.getInt("sensor_mask"))

        gw.onCommand("""{"id":4,"client":"ha","op":"sensor_stream","mask":0}""")
        assertEquals(CondorProtocol.MSG_UNSUBSCRIBE, frameType(sent[3]))
        assertEquals("1|SensorData.b|{}", frameText(sent[3]))
        gw.onResponse(0, "")
        assertEquals(0, last("resp").json.getInt("status"))
        assertEquals(0, last("link").json.getInt("sensor_mask"))
    }

    @Test
    fun sensorStreamSubscribeDeclined() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"sensor_stream","mask":3}""")
        gw.onResponse(3, "")
        assertEquals(1, sent.size)
        assertEquals(3, last("resp").json.getInt("status"))
        assertEquals(0, gw.sensorMask)
    }

    @Test
    fun newSessionResetsSensorMask() {
        readySession()
        gw.onCommand("""{"id":1,"client":"ha","op":"sensor_stream","mask":3}""")
        gw.onResponse(0, "")
        gw.onResponse(0, "")
        assertEquals(3, gw.sensorMask)
        gw.sessionDown()
        gw.sessionUp()
        assertEquals(0, last("link").json.getInt("sensor_mask"))
    }

    @Test
    fun responseWithoutRequestIsIgnored() {
        readySession()
        gw.onResponse(0, "{}")
        assertTrue(msgs.isEmpty())
    }
}
