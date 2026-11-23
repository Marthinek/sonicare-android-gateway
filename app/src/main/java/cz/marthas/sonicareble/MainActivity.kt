package cz.marthas.sonicareble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Environment
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import cz.marthas.sonicareble.condor.CondorProtocol
import java.text.SimpleDateFormat
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SonicareBLE"
        private const val APP_VERSION = "0.1.34"

        private const val PERMISSION_REQUEST_CODE = 1001

        private val CONDOR_SERVICE_UUID =
            UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")

        private val CHAR_WRITE_NR_UUID =
            UUID.fromString("e50b0001-af04-4564-92ad-fef019489de6")

        private val CHAR_NOTIFY_RX_UUID =
            UUID.fromString("e50b0002-af04-4564-92ad-fef019489de6")

        private val CHAR_NOTIFY_TX_UUID =
            UUID.fromString("e50b0003-af04-4564-92ad-fef019489de6")

        private val CHAR_WRITE_CFG_UUID =
            UUID.fromString("e50b0004-af04-4564-92ad-fef019489de6")

        private val CHAR_PROTO_CFG_UUID =
            UUID.fromString("e50b0005-af04-4564-92ad-fef019489de6")

        private val CHAR_SERVER_CFG_UUID =
            UUID.fromString("e50b0006-af04-4564-92ad-fef019489de6")

        private val CHAR_CLIENT_CFG_UUID =
            UUID.fromString("e50b0007-af04-4564-92ad-fef019489de6")

        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")


        private val DEVICE_INFORMATION_SERVICE_UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isGattConnected = false

    // Connection guard: prevents a second connectGatt() while the first
    // connection is still being established.
    private var gattConnectInProgress = false
    private var gattConnectionGeneration = 0

    // One controlled retry for transient GATT_ERROR (257) during the
    // early Condor handshake. Manual Connect resets this retry budget.
    private var condorEarlyConnectRetryUsed = false
    private val condorEarlyConnectRetryDelayMs = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    private lateinit var mqttManager: MqttManager

    // Full diagnostic log is written continuously to the phone storage so
    // long brushing sessions are not lost because of clipboard size limits.
    private val logFileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var logFileWriter: BufferedWriter? = null
    private var logFileDisplayName: String = ""
    private var logFileUri: android.net.Uri? = null

    private var selectedDevice: BluetoothDevice? = null
    private var isScanning = false

    // Automatic foreground mode: keep looking for the Sonicare after launch
    // and reconnect after the toothbrush drops the idle BLE connection.
    private val autoConnectEnabled = true
    private val autoScanRetryDelayMs = 10_000L
    private var manualDisconnectRequested = false
    private val autoScanRestartRunnable = Runnable {
        if (autoConnectEnabled && !isGattConnected && !gattConnectInProgress && !isScanning) {
            log("AUTO: restarting BLE scan")
            startScan()
        }
    }

    /*
     * Condor V4 handshake state.
     *
     * 1 = SERVER_CFG enabled, waiting for version 04
     * 2 = TX notify enabled, waiting for RX_ACK notify
     * 3 = RX_ACK notify enabled, ready to send FF FF FF FF
     * 4 = FF FF FF FF sent, waiting for 6-byte SERVER_CFG
     * 5 = 6-byte channel configuration received, waiting for RX open
     * 6 = RX channel opened with 40, waiting for RX_ACK
     * 7 = Condor V4 handshake reached RX_ACK
     * 8 = InitializeReq sent, waiting for framed TX response
     */
    private var condorStage = 0

    /*
     * Condor framed transport state.
     *
     * After the channel-open ACK, outgoing app->brush frames use a
     * 1-byte transport header followed by:
     *
     *   FE FF <type> <len_hi> <len_lo> <body...>
     *
     * The channel config reported 244 bytes, but with the default Android
     * ATT MTU of 23 bytes we conservatively use 20 bytes total per BLE
     * write, leaving 19 bytes for the framed payload.
     */
    private var condorNextTxSeq = 1
    private var condorRxFrameBuffer = ByteArray(0)

    // Condor notifications can occasionally be delivered more than once by
    // the Android BLE stack. The upstream implementation treats an identical
    // delivery within 100 ms as a host-side duplicate: ACK it only once and
    // never append its payload to the frame buffer twice. An identical packet
    // arriving after 100 ms is treated as a genuine device resend and is ACKed
    // again, but still must not be reassembled twice.
    private var condorLastTxNotificationValue: ByteArray? = null
    private var condorLastTxNotificationAtMs = 0L
    private var condorTxNotificationCopiesDropped = 0
    private var condorTxNotificationResends = 0
    private val condorTxNotificationCopyWindowMs = 100L

    // Passive diagnostic timing. This watchdog never sends BLE traffic.
    // It only records whether the GATT connection is still alive and how
    // long it has been since the last Condor notification.
    private var condorLastActivityAtMs = 0L
    private var condorIdleWatchdogRunning = false
    private val condorIdleWatchdogIntervalMs = 5_000L
    private val condorIdleWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!condorIdleWatchdogRunning) {
                return
            }

            if (isGattConnected && bluetoothGatt != null) {
                val now = SystemClock.elapsedRealtime()
                val idleMs =
                    if (condorLastActivityAtMs == 0L) {
                        -1L
                    } else {
                        now - condorLastActivityAtMs
                    }

                log(
                    "CONDOR IDLE WATCHDOG: connected=true; " +
                        "subscribed=${condorSubscribedPorts.size}; " +
                        "pending=${condorPendingRequest}; " +
                        "lastActivityMs=$idleMs"
                )
            } else {
                log("CONDOR IDLE WATCHDOG: connected=false")
            }

            handler.postDelayed(
                this,
                condorIdleWatchdogIntervalMs
            )
        }
    }

    // Android GATT writes must be paced one operation at a time. The original
    // implementation sent all Condor fragments back-to-back, which can make
    // Android reject the second WRITE_NO_RESPONSE with GATT error 201.
    //
    // We intentionally do NOT wait for the Condor RX_ACK between fragments.
    // The upstream implementation serializes BLE writes by awaiting the
    // completion of each write operation; the next fragment is therefore
    // started from onCharacteristicWrite().
    private val condorTxFrameQueue = java.util.ArrayDeque<ByteArray>()
    private var condorTxActiveFrame: ByteArray? = null
    private var condorTxActiveOffset = 0
    private var condorTxWriteInFlight = false

    // Serialize ALL Condor characteristic writes through one Android GATT queue.
    // e50b0001 (RX frame) and e50b0004 (TX transport ACK) otherwise race each
    // other and Android can return ERROR_GATT_WRITE_REQUEST_BUSY (201).
    private enum class CondorGattWriteKind {
        RX_FRAME,
        TX_ACK
    }

    private data class CondorGattWriteOp(
        val kind: CondorGattWriteKind,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val description: String,
        val frameEndOffset: Int = 0,
        val frameSeq: Int = -1,
        var retryCount: Int = 0
    )

    private val condorGattWriteQueue =
        java.util.ArrayDeque<CondorGattWriteOp>()

    private var condorGattWriteInFlight: CondorGattWriteOp? = null
    private var condorGattWriteRetryScheduled = false
    private val condorGattWriteRetryDelayMs = 75L
    private val condorGattWriteMaxBusyRetries = 20

    // RX_FRAME writes use 100 ms pacing as a diagnostic experiment to
    // reduce Android GATT write congestion. TX_ACK writes bypass this
    // pacing because Condor requires prompt transport acknowledgements.
    private var condorGattWritePacingScheduled = false
    private val condorGattWritePacingDelayMs = 100L
    private var condorGattWriteBusyCount = 0

    // Short rolling history of the last GATT operations. This is diagnostic
    // only: it does not change timing or protocol behavior. It lets us see
    // exactly what Android accepted immediately before a GATT 257 disconnect.
    private val condorGattHistory = ArrayDeque<String>()
    private val condorGattHistoryMaxEntries = 30

    private val condorTxWriteTimeoutMs = 5_000L
    private val condorTxWriteTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt
        val op = condorGattWriteInFlight

        if (op != null && gatt != null) {
            log(
                "CONDOR GATT WRITE TIMEOUT after " +
                    "${condorTxWriteTimeoutMs} ms; " +
                    "callback did not arrive for ${op.description}"
            )

            condorGattWriteInFlight = null
            condorTxWriteInFlight = false
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
            condorGattWriteQueue.clear()

            log(
                "CONDOR GATT WRITE: cleared write queue after timeout"
            )
        }
    }

    // Protocol discovery state. The actual HX741X product/port names are
    // discovered from the toothbrush instead of being hard-coded.
    private enum class CondorPendingRequest {
        NONE,
        GET_PRODUCTS,
        GET_PORTS,
        GET_PROPS,
        SUBSCRIBE
    }

    private var condorPendingRequest = CondorPendingRequest.NONE
    private val condorProductIds = mutableListOf<String>()
    private val condorPorts = mutableListOf<String>()
    private var condorProductIndex = 0
    private var condorPortIndex = 0
    private var condorCurrentProductId: String? = null
    private var condorCurrentPort: String? = null
    private var condorDiscoveryActive = false

    // Diagnostic 15-second idle keep-alive. This deliberately uses a normal
    // Condor GetProps request instead of inventing a new protocol command.
    // The request is sent only after discovery is complete and is handled
    // separately from the discovery state machine.
    private var condorIdleKeepAlivePending = false
    private var condorIdleKeepAliveCount = 0
    private var condorIdleKeepAliveLastResponseAtMs = 0L
private var condorDiagLastTxNotificationAtMs = 0L
    private var condorDiagLastTxNotificationHex = ""
    private var condorDiagLastChangeIndicationAtMs = 0L
    private var condorDiagLastChangeIndicationHex = ""
    private var condorDiagLastChangeIndicationAckAtMs = 0L
    private var condorDiagLastSuccessfulGattWriteAtMs = 0L
    private var condorDiagLastSuccessfulGattWriteDescription = ""
    private var condorDiagLastGattBusyAtMs = 0L
    private var condorDiagLastGattBusyDescription = ""
    private var condorDiagLastGattCallbackAtMs = 0L
    private var condorDiagLastGattCallbackStatus = -1
    private var condorDiagLastGattCallbackState = -1
    private var condorDiagGattBusyTotal = 0
    private var condorDiagLastGenericRespAtMs = 0L
    private var condorDiagLastGenericRespSummary = ""
    private var condorDiagLastBleNotificationAtMs = 0L
    private var condorDiagLastBleNotificationUuid = ""

    // Diagnostic mode: probe the link every 5 seconds. The previous 15-second
    // interval allowed the toothbrush to remain quiet for long enough that we
    // could not tell whether the 257 disconnect happened because of that idle
    // period or immediately after the brush accepted a request.
    private val condorIdleKeepAliveIntervalMs = 5_000L
    private val condorIdleKeepAliveRunnable = object : Runnable {
        override fun run() {
            val gatt = bluetoothGatt
            if (gatt != null && isGattConnected) {
                sendCondorIdleKeepAlive(gatt)
            } else {
                log("CONDOR IDLE KEEPALIVE: skipped; GATT disconnected")
            }
        }
    }

    private var condorSubscribeIndex = 0
    private val condorSubscribedPorts = mutableListOf<Pair<String, String>>()

    // Human-readable record of every successful GetProps response.
    private val condorDiscoveredProps = mutableListOf<String>()

    private val scanDurationMs = 5_000L
    private val scanPauseDurationMs = 10_000L

    private val timeFormat =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            }

            val rssi = result.rssi

            val manufacturerData = result.scanRecord
                ?.manufacturerSpecificData

            val rawAdvertisement = result.scanRecord?.bytes

            val advertisedServiceUuids =
                result.scanRecord?.serviceUuids
                    ?.map { it.uuid }
                    ?: emptyList()

            val containsCondorName =
                rawAdvertisement?.let {
                    bytesToAscii(it).contains("Condor", ignoreCase = true)
                } == true

            val containsCondorService =
                advertisedServiceUuids.any {
                    it == CONDOR_SERVICE_UUID
                }

            val containsCondor =
                containsCondorName || containsCondorService

            log(
                "SCAN: ${device.address} " +
                    "RSSI=$rssi " +
                    "name=${name ?: "<null>"}" +
                    if (containsCondor) " [CONDOR]" else ""
            )

            if (containsCondorService) {
                log(
                    "SCAN SERVICE UUID: " +
                        advertisedServiceUuids.joinToString(", ")
                )
            }

            if (containsCondor ||
                name?.contains("Sonicare", ignoreCase = true) == true
            ) {
                if (selectedDevice == null) {
                    selectedDevice = device

                    log(
                        "SCAN: selected ${device.address} " +
                            "name=${name ?: "<null>"}"
                    )

                    log("SCAN: Sonicare selected -> stopping scan before GATT connect")
                    stopScan()

                    updateStatus(
                        "Nalezeno: ${name ?: "Philips Sonicare"} " +
                            "(${device.address})"
                    )

                    if (autoConnectEnabled &&
                        bluetoothGatt == null &&
                        !gattConnectInProgress
                    ) {
                        log("AUTO: Sonicare found -> starting GATT connect")
                        handler.post {
                            connectSelectedDevice()
                        }
                    }
                }
            }

            if (containsCondor) {
                val bytes = result.scanRecord?.bytes

                if (bytes != null) {
                    log(
                        "SCAN ADV HEX: ${bytesToHex(bytes)}"
                    )

                    val ascii = bytesToAscii(bytes)

                    if (ascii.isNotBlank()) {
                        log(
                            "SCAN ADV ASCII: $ascii"
                        )
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("SCAN FAILED: errorCode=$errorCode")
            stopScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createUi()

        mqttManager = MqttManager(this) { message ->
            log(message)
        }

        val savedMqttConfig = mqttManager.loadConfig()
        if (savedMqttConfig.enabled && savedMqttConfig.host.isNotBlank()) {
            log("MQTT: enabled -> connecting to saved broker")
            mqttManager.connect(savedMqttConfig) { connected, message ->
                runOnUiThread {
                    updateStatus(
                        if (connected) "MQTT připojeno" else "MQTT: $message"
                    )
                }
            }
        }

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE)
                    as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        requestBlePermissionsIfNeeded()
        initLogFile()
        if (logFileDisplayName.isNotBlank()) {
            log("LOG FILE: Downloads/SonicareBleDiagnostic/$logFileDisplayName")
        } else {
            log("LOG FILE: unable to create diagnostic log file")
        }

        if (autoConnectEnabled && blePermissionsGranted()) {
            log("AUTO: enabled -> starting BLE scan")
            handler.post {
                if (!isGattConnected && !gattConnectInProgress && !isScanning) {
                    startScan()
                }
            }
        } else if (autoConnectEnabled) {
            log("AUTO: waiting for BLE permissions")
        }

        log("=== SONICARE BLE DIAGNOSTIC v$APP_VERSION ===")
        log("Scanner version=$APP_VERSION")
        log("Android SDK=${Build.VERSION.SDK_INT}")
        log("Device=${Build.MODEL}")
        log("Manufacturer=${Build.MANUFACTURER}")
    }

    private fun createUi() {

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        // Slightly more space at the top so the controls are comfortably
        // reachable when the Pixel is held vertically.
        root.setPadding(24, 48, 24, 24)

        statusView = TextView(this)
        statusView.text = "Status: připraven"
        statusView.textSize = 16f

        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val versionView = TextView(this)
        versionView.text = "Sonicare BLE Scanner v$APP_VERSION"
        versionView.textSize = 12f
        versionView.setPadding(0, 4, 0, 12)

        root.addView(
            versionView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val copyButton = Button(this)
        copyButton.text = "Kopírovat log"

        copyButton.setOnClickListener {
            copyLogToClipboard()
        }

        root.addView(copyButton)

        val saveInfoButton = Button(this)
        saveInfoButton.text = "Log uložený v Downloads"
        saveInfoButton.setOnClickListener {
            updateStatus(
                if (logFileDisplayName.isNotBlank()) {
                    "Log se ukládá do Downloads: $logFileDisplayName"
                } else {
                    "Logový soubor zatím není dostupný"
                }
            )
        }

        root.addView(saveInfoButton)

        val clearButton = Button(this)
        clearButton.text = "Vymazat log"

        clearButton.setOnClickListener {
            logView.text = ""
        }

        root.addView(clearButton)

        val mqttSettingsButton = Button(this)
        mqttSettingsButton.text = "MQTT nastavení"
        mqttSettingsButton.setOnClickListener {
            showMqttSettingsDialog()
        }
        root.addView(mqttSettingsButton)

        val mqttTestButton = Button(this)
        mqttTestButton.text = "TEST MQTT CONNECTION"
        mqttTestButton.setOnClickListener {
            val config = mqttManager.loadConfig()

            if (config.host.isBlank()) {
                showMqttSettingsDialog()
            } else {
                updateStatus("Testuji MQTT...")
                mqttManager.testConnection(config) { success, message ->
                    runOnUiThread {
                        updateStatus(
                            if (success) "MQTT test OK" else "MQTT test: $message"
                        )
                    }
                }
            }
        }
        root.addView(mqttTestButton)

        logView = TextView(this)
        logView.textSize = 12f
        logView.setTextIsSelectable(true)
        logView.setPadding(0, 16, 0, 0)

        val scrollView = ScrollView(this)
        scrollView.addView(logView)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun showMqttSettingsDialog() {
        val config = mqttManager.loadConfig()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 24, 48, 8)

        fun addField(
            label: String,
            value: String,
            inputType: Int = InputType.TYPE_CLASS_TEXT
        ): EditText {
            val labelView = TextView(this)
            labelView.text = label
            labelView.setPadding(0, 12, 0, 4)
            container.addView(labelView)

            val field = EditText(this)
            field.setText(value)
            field.inputType = inputType
            container.addView(field)
            return field
        }

        val enabledCheckBox = CheckBox(this)
        enabledCheckBox.text = "Povolit MQTT"
        enabledCheckBox.isChecked = config.enabled
        container.addView(enabledCheckBox)

        val hostField = addField(
            "Broker",
            config.host,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        val portField = addField(
            "Port",
            config.port.toString(),
            InputType.TYPE_CLASS_NUMBER
        )

        val usernameField = addField(
            "Uživatel",
            config.username
        )

        val passwordField = addField(
            "Heslo",
            config.password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val topicField = addField(
            "Base topic",
            config.baseTopic
        )

        val tlsCheckBox = CheckBox(this)
        tlsCheckBox.text = "TLS / SSL"
        tlsCheckBox.isChecked = config.tls
        container.addView(tlsCheckBox)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("MQTT nastavení")
            .setView(container)
            .setNegativeButton("Zrušit", null)
            .setPositiveButton("Uložit", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val host = hostField.text.toString().trim()
                    val port = portField.text.toString().trim().toIntOrNull()
                    val username = usernameField.text.toString()
                    val password = passwordField.text.toString()
                    val baseTopic = topicField.text.toString().trim()

                    if (host.isBlank()) {
                        hostField.error = "Zadej adresu MQTT brokeru"
                        return@setOnClickListener
                    }

                    if (port == null || port !in 1..65535) {
                        portField.error = "Neplatný port"
                        return@setOnClickListener
                    }

                    if (baseTopic.isBlank()) {
                        topicField.error = "Zadej base topic"
                        return@setOnClickListener
                    }

                    val newConfig = MqttConfig(
                        enabled = enabledCheckBox.isChecked,
                        host = host,
                        port = port,
                        username = username,
                        password = password,
                        baseTopic = baseTopic,
                        tls = tlsCheckBox.isChecked
                    )

                    mqttManager.saveConfig(newConfig)

                    if (newConfig.enabled) {
                        updateStatus("Připojuji MQTT...")
                        mqttManager.connect(newConfig) { connected, message ->
                            runOnUiThread {
                                updateStatus(
                                    if (connected) "MQTT připojeno" else "MQTT: $message"
                                )
                            }
                        }
                    } else {
                        mqttManager.disconnect()
                        updateStatus("MQTT vypnuto")
                    }

                    log(
                        "MQTT config saved: enabled=${newConfig.enabled}, " +
                            "broker=${newConfig.host}:${newConfig.port}, " +
                            "baseTopic=${newConfig.baseTopic}, tls=${newConfig.tls}"
                    )

                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    private fun blePermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            val missing = permissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isNotEmpty()) {
                requestPermissions(
                    missing.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {

            if (
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (blePermissionsGranted()) {
                log("BLE permissions granted")
                if (autoConnectEnabled && !isGattConnected && !gattConnectInProgress && !isScanning) {
                    log("AUTO: permissions ready -> starting BLE scan")
                    startScan()
                }
            } else {
                log("BLE permissions denied -> automatic scan disabled")
                updateStatus("BLE oprávnění nebyla povolena")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {

        if (!bluetoothAdapter.isEnabled) {
            log("SCAN: Bluetooth is disabled")
            updateStatus("Bluetooth je vypnutý")
            return
        }

        selectedDevice = null

        stopScan()

        log("")
        log("=== BLE SCAN START ===")

        updateStatus("Skenuji BLE...  [Scanner v$APP_VERSION]")

        isScanning = true

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0L)
            .build()

        log(
                "SCAN settings: LOW_POWER, reportDelay=0ms, " +
                    "duration=${scanDurationMs}ms; pause=${scanPauseDurationMs}ms; " +
                    "bonded fallback enabled"
            )

        bleScanner?.startScan(
            null,
            scanSettings,
            scanCallback
        )

        handler.postDelayed({
            if (!isScanning) {
                return@postDelayed
            }

            stopScan()

            // A bonded Sonicare may currently be connected by another Philips
            // app/service and therefore may not be advertising at all. In that
            // case the normal BLE scanner cannot report it. Use the bonded LE
            // device list only as a fallback when the scan found nothing.
            if (selectedDevice == null) {
                selectBondedSonicareFallback()
            }

            if (selectedDevice != null) {
                updateStatus(
                    "Nalezeno zařízení: ${selectedDevice?.address}"
                )

                if (autoConnectEnabled &&
                    bluetoothGatt == null &&
                    !gattConnectInProgress
                ) {
                    log("AUTO: scan finished with device -> starting GATT connect")
                    connectSelectedDevice()
                }
            } else if (autoConnectEnabled) {
                updateStatus("Sonicare nenalezena – další scan za ${scanPauseDurationMs} ms")
                log("AUTO: no Sonicare found -> waiting ${scanPauseDurationMs} ms before next scan")
                handler.postDelayed(autoScanRestartRunnable, scanPauseDurationMs)
            } else {
                updateStatus("Sonicare nenalezena")
            }
        }, scanDurationMs)
    }

    @SuppressLint("MissingPermission")
    private fun selectBondedSonicareFallback() {
        if (selectedDevice != null) {
            return
        }

        val bondedDevices = try {
            bluetoothAdapter.bondedDevices
        } catch (e: SecurityException) {
            log("SCAN fallback: BLUETOOTH_CONNECT permission denied")
            return
        } catch (e: Exception) {
            log("SCAN fallback: cannot read bonded devices: ${e.message}")
            return
        }

        if (bondedDevices.isEmpty()) {
            log("SCAN fallback: no bonded Bluetooth devices")
            return
        }

        log("SCAN fallback: checking ${bondedDevices.size} bonded device(s)")

        val candidate = bondedDevices.firstOrNull { device ->
            val name = try {
                device.name
            } catch (_: SecurityException) {
                null
            }

            name?.contains("Sonicare", ignoreCase = true) == true ||
                name?.contains("Philips", ignoreCase = true) == true
        }

        if (candidate != null) {
            selectedDevice = candidate

            val name = try {
                candidate.name
            } catch (_: SecurityException) {
                null
            }

            log(
                "SCAN fallback: bonded Sonicare selected -> " +
                    "${candidate.address} name=${name ?: "<null>"}"
            )
            log(
                "SCAN fallback: device may currently be connected by " +
                    "another Philips service and therefore not advertising"
            )

            updateStatus(
                "Nalezena spárovaná Sonicare: " +
                    "${name ?: "Philips Sonicare"} (${candidate.address})"
            )
        } else {
            log("SCAN fallback: no bonded Sonicare/Philips device found")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {

        if (!isScanning) {
            return
        }
