package cz.marthas.sonicareble.condor

import org.json.JSONObject

/**
 * Philips Sonicare Condor framed protocol used by HX741X / Series 7100.
 *
 * This class intentionally contains only protocol definitions and payload
 * construction. BLE/GATT transport remains in MainActivity for now; the next
 * refactor can move that transport into CondorTransport.
 */
object CondorProtocol {
    const val MSG_INITIALIZE_REQ = 1
    const val MSG_INITIALIZE_RESP = 2
    const val MSG_PUT_PROPS = 3
    const val MSG_GET_PROPS = 4
    const val MSG_SUBSCRIBE = 5
    const val MSG_UNSUBSCRIBE = 6
    const val MSG_GENERIC_RESP = 7
    const val MSG_CHANGE_IND = 8
    const val MSG_CHANGE_IND_RESP = 9
    const val MSG_GET_PRODUCTS = 10
    const val MSG_GET_PORTS = 11

    const val STATUS_OK = 0
    const val SEQ_MASK = 0x3F
    const val SUBSCRIBE_TIMEOUT_SECS = 31_536_000L // 1 year

    /** Ports used by the upstream Condor implementation for live updates. */
    val DEFAULT_SUBSCRIBE_PORTS: List<Pair<String, String>> = listOf(
        "1" to "Sonicare",
        "1" to "RoutineStatus",
        "1" to "Battery",
        "1" to "BrushHead",
        "1" to "SessionStorage"
    )

    fun buildFrame(messageType: Int, body: ByteArray = ByteArray(0)): ByteArray {
        require(messageType in 0..255)
        require(body.size <= 0xFFFF)
        return ByteArray(5 + body.size).also { frame ->
            frame[0] = 0xFE.toByte()
            frame[1] = 0xFF.toByte()
            frame[2] = messageType.toByte()
            frame[3] = ((body.size ushr 8) and 0xFF).toByte()
            frame[4] = (body.size and 0xFF).toByte()
            body.copyInto(frame, 5)
        }
    }

    fun initializeReq(): ByteArray = buildFrame(MSG_INITIALIZE_REQ)
    fun getProductsReq(): ByteArray = buildFrame(MSG_GET_PRODUCTS)

    fun getPortsReq(productId: String): ByteArray =
        buildFrame(MSG_GET_PORTS, nulTerminatedAscii(productId))

    fun getPropsReq(productId: String, port: String): ByteArray =
        buildFrame(
            MSG_GET_PROPS,
            nulTerminatedAscii(productId) + nulTerminatedAscii(port)
        )

    /** MSG_SUBSCRIBE payload: product NUL port NUL {"timeout":N} */
    fun subscribeReq(productId: String, port: String): ByteArray {
        val body = JSONObject()
            .put("timeout", SUBSCRIBE_TIMEOUT_SECS)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val payload =
            nulTerminatedAscii(productId) +
                nulTerminatedAscii(port) +
                body

        return buildFrame(MSG_SUBSCRIBE, payload)
    }

    fun unsubscribeReq(productId: String, port: String): ByteArray =
        buildFrame(
            MSG_UNSUBSCRIBE,
            nulTerminatedAscii(productId) +
                nulTerminatedAscii(port) +
                "{}".toByteArray(Charsets.UTF_8)
        )

    /** Exact two-byte ACK used by the upstream implementation. */
    fun changeIndicationAck(): ByteArray =
        buildFrame(MSG_CHANGE_IND_RESP, byteArrayOf(STATUS_OK.toByte(), 0))

    fun messageTypeName(type: Int): String = when (type) {
        MSG_INITIALIZE_REQ -> "InitializeReq"
        MSG_INITIALIZE_RESP -> "InitializeResp"
        MSG_PUT_PROPS -> "PutProps"
        MSG_GET_PROPS -> "GetProps"
        MSG_SUBSCRIBE -> "Subscribe"
        MSG_UNSUBSCRIBE -> "Unsubscribe"
        MSG_GENERIC_RESP -> "GenericResp"
        MSG_CHANGE_IND -> "ChangeIndication"
        MSG_CHANGE_IND_RESP -> "ChangeIndicationResponse"
        MSG_GET_PRODUCTS -> "GetProducts"
        MSG_GET_PORTS -> "GetPorts"
        else -> "Unknown"
    }

    private fun nulTerminatedAscii(value: String): ByteArray =
        value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
}
