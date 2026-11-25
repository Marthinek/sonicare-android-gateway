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
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
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
import androidx.core.content.ContextCompat
import cz.marthas.sonicareble.condor.CondorMqttGateway
import cz.marthas.sonicareble.condor.CondorProtocol
import java.text.SimpleDateFormat
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SonicareBLE"
        private const val APP_VERSION = "0.1.35-queuefix5"

        private const val PERMISSION_REQUEST_CODE = 1001
        private const val UI_LOG_FLUSH_INTERVAL_MS = 100L
        private const val UI_LOG_MAX_CHARS = 120_000
        private const val UI_LOG_BATCH_MAX_LINES = 250
        private const val UI_LOG_BATCH_MAX_CHARS = 24_000

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

    private var bleService: SonicareBleForegroundService? = null
    private var isBleServiceBound = false
    private var isBleServiceBinding = false
    private var localBluetoothGatt: BluetoothGatt? = null

    // Keep existing protocol code source-compatible while the actual GATT
    // object is owned by SonicareBleForegroundService.
    private var bluetoothGatt: BluetoothGatt?
        get() = bleService?.getBluetoothGatt() ?: localBluetoothGatt
        set(value) {
            localBluetoothGatt = value
            bleService?.setBluetoothGatt(value)
        }

    private val bleServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SonicareBleForegroundService.LocalBinder
            val service = localBinder?.getService()
            if (service == null) {
                log("FOREGROUND SERVICE: invalid binder received")
                isBleServiceBinding = false
                return
            }

            bleService = service
            isBleServiceBound = true
            isBleServiceBinding = false
            service.setLogListener { message ->
                handler.post {
                    log("BLE SERVICE: $message")
                }
            }
            service.setGattCallbackDelegate(gattCallback)

            val error = service.getForegroundStartError()
            log(
                if (service.isForegroundActive()) {
                    "FOREGROUND SERVICE: BOUND and ACTIVE (connectedDevice)"
                } else {
                    "FOREGROUND SERVICE: BOUND but not active; error=${error ?: "unknown"}"
                }
            )

            // Wait briefly for onStartCommand() to finish startForeground().
            // Do not open a BLE connection from a merely bound (background-
            // unprotected) service if Android rejected foreground promotion.
            if (autoConnectEnabled && blePermissionsGranted()) {
                handler.postDelayed({
                    if (bleService !== service) return@postDelayed

                    if (!service.isForegroundActive()) {
                        val startError = service.getForegroundStartError()
                        log(
                            "AUTO: BLE scan blocked because foreground service " +
                                "is not active; error=${startError ?: "not reported"}"
                        )
                        updateStatus("BLE foreground služba není aktivní")
                        return@postDelayed
                    }

                    if (!isGattConnected && !gattConnectInProgress && !isScanning) {
                        log("AUTO: foreground service active and bound -> starting BLE scan")
                        startScan()
                    }
                }, 300L)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("FOREGROUND SERVICE: disconnected from service binder")
            bleService = null
            localBluetoothGatt = null
            isGattConnected = false
            gattConnectInProgress = false
            isBleServiceBound = false
            isBleServiceBinding = false
        }

        override fun onBindingDied(name: ComponentName?) {
            log("FOREGROUND SERVICE: service binding died")
            bleService = null
            localBluetoothGatt = null
            isGattConnected = false
            gattConnectInProgress = false
            isBleServiceBound = false
            isBleServiceBinding = false
        }

        override fun onNullBinding(name: ComponentName?) {
            log("FOREGROUND SERVICE: service returned a null binding")
            bleService = null
            localBluetoothGatt = null
            isBleServiceBound = false
            isBleServiceBinding = false
        }
    }

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

    // Keep BLE callbacks free from expensive TextView append/scroll work.
    // The complete diagnostic stream is still written to the Downloads log;
    // this queue only feeds a bounded on-screen preview in batches.
    private val pendingUiLogLines = ConcurrentLinkedQueue<String>()
    private val uiLogFlushScheduled = AtomicBoolean(false)
    private val uiLogFlushRunnable = object : Runnable {
        override fun run() {
            val batch = StringBuilder()
            var lineCount = 0

            while (
                lineCount < UI_LOG_BATCH_MAX_LINES &&
                batch.length < UI_LOG_BATCH_MAX_CHARS
            ) {
                val line = pendingUiLogLines.poll() ?: break
                batch.append(line).append('\n')
                lineCount++
            }

            if (batch.isNotEmpty() && this@MainActivity::logView.isInitialized) {
                val currentText = logView.text
                val projectedLength = currentText.length + batch.length

                if (projectedLength > UI_LOG_MAX_CHARS) {
                    val targetOldLength =
                        (UI_LOG_MAX_CHARS - batch.length).coerceAtLeast(0)
                    val dropUntil =
                        (currentText.length - targetOldLength).coerceAtLeast(0)
                    val nextLine = currentText.indexOf('\n', dropUntil)
                    val keepFrom = if (nextLine >= 0) nextLine + 1 else dropUntil

                    logView.text =
                        currentText.subSequence(keepFrom, currentText.length).toString() +
                            batch.toString()
                } else {
                    logView.append(batch.toString())
                }

                val scrollView = logView.parent as? ScrollView
                scrollView?.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }

            uiLogFlushScheduled.set(false)
            if (pendingUiLogLines.isNotEmpty()) {
                scheduleUiLogFlush()
            }
        }
    }

    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    private lateinit var mqttManager: MqttManager

    // MQTT contract of the philips_sonicare_ble Home Assistant integration.
    private lateinit var mqttGateway: CondorMqttGateway

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
    private data class CondorUrgentTxFrame(
        val value: ByteArray,
        val description: String,
        val enqueuedAtElapsedMs: Long
    )

    // Urgent protocol replies are selected before normal frames at the next
    // complete Condor-frame boundary. Never interleave fragments of frames.
    private val condorUrgentTxFrameQueue =
        java.util.ArrayDeque<CondorUrgentTxFrame>()
    private val condorTxFrameQueue = java.util.ArrayDeque<ByteArray>()
    private var condorTxActiveFrame: ByteArray? = null
    private var condorTxActiveFrameIsUrgent = false
    private var condorTxActiveFrameDescription = ""
    private var condorTxActiveFrameEnqueuedAtMs = 0L
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
    // Protect the API 33+ immediate-completion path from re-entrant/concurrent pumps.
    private var condorGattWritePumpRunning = false
    private var condorGattWritePumpRequested = false
    private var condorGattWriteRetryScheduled = false
    private val condorGattWriteRetryDelayMs = 75L
    private val condorGattWriteMaxBusyRetries = 20

    // RX_FRAME writes use 100 ms pacing as a diagnostic experiment to
    // reduce Android GATT write congestion. TX_ACK writes bypass this
    // pacing because Condor requires prompt transport acknowledgements.
    private var condorGattWritePacingScheduled = false
    private val condorGattWritePacingDelayMs = 100L
    private val condorGattWritePriorityFrameDispatchDelayMs = 10L
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
            condorUrgentTxFrameQueue.clear()
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
        SUBSCRIBE,
        // A request from Home Assistant (MQTT <base>/cmd); its answer goes
        // to mqttGateway.
        GATEWAY_CMD
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
        mqttGateway = createMqttGateway()

        val savedMqttConfig = mqttManager.loadConfig()
        if (savedMqttConfig.enabled && savedMqttConfig.host.isNotBlank()) {
            log("MQTT: enabled -> connecting to saved broker")
            connectMqtt(savedMqttConfig)
        }

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE)
                    as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter

        requestBlePermissionsIfNeeded()
        initLogFile()
        if (logFileDisplayName.isNotBlank()) {
            log("LOG FILE: Downloads/SonicareBleDiagnostic/$logFileDisplayName")
        } else {
            log("LOG FILE: unable to create diagnostic log file")
        }

        log("=== SONICARE BLE DIAGNOSTIC v$APP_VERSION ===")
        log("Scanner version=$APP_VERSION")
        log("Android SDK=${Build.VERSION.SDK_INT}")
        log("Device=${Build.MODEL}")
        log("Manufacturer=${Build.MANUFACTURER}")

        if (blePermissionsGranted()) {
            startBleForegroundServiceIfReady()
        } else if (autoConnectEnabled) {
            log("AUTO: waiting for BLE permissions before foreground service and scan")
        }
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

    private fun createMqttGateway(): CondorMqttGateway {
        val brush = object : CondorMqttGateway.Brush {
            override fun isIdle(): Boolean =
                bluetoothGatt != null &&
                    isGattConnected &&
                    condorPendingRequest == CondorPendingRequest.NONE &&
                    !condorDiscoveryActive &&
                    !condorIdleKeepAlivePending

            override fun send(frame: ByteArray, description: String): Boolean {
                val gatt = bluetoothGatt
                if (gatt == null || !isGattConnected) return false
                condorPendingRequest = CondorPendingRequest.GATEWAY_CMD
                log("MQTT GATEWAY: $description")
                sendCondorFrame(gatt, frame, description = description)
                return true
            }

            override fun abandon() {
                if (condorPendingRequest == CondorPendingRequest.GATEWAY_CMD) {
                    condorPendingRequest = CondorPendingRequest.NONE
                }
            }
        }

        val timer = CondorMqttGateway.Timer { delayMs, action ->
            val runnable = Runnable { action() }
            handler.postDelayed(runnable, delayMs)
            return@Timer { handler.removeCallbacks(runnable) }
        }

        // ind/*/SensorData.b comes several times per second; keep it out of
        // the log.
        val publisher = CondorMqttGateway.Publisher { topic, payload, qos, retain ->
            if (mqttManager.isConnected()) {
                mqttManager.publish(
                    topic,
                    payload,
                    retained = retain,
                    qos = qos,
                    quiet = topic.endsWith(".b")
                )
            }
        }

        return CondorMqttGateway(
            out = publisher,
            brush = brush,
            timer = timer,
            gatewayName = "Sonicare BLE Gateway (Android)",
            gatewayVersion = APP_VERSION,
            log = { log(it) }
        )
    }

    /** Connect to the broker, with the gateway's last will once the brush is known. */
    private fun connectMqtt(config: MqttConfig) {
        val willTopic = mqttGateway.willTopic
        val session = willTopic?.let {
            MqttSession(it, "offline") {
                handler.post { mqttGateway.onMqttConnected() }
            }
        }
        mqttManager.connect(config, session) { connected, message ->
            runOnUiThread {
                updateStatus(
                    if (connected) "MQTT připojeno" else "MQTT: $message"
                )
            }
        }
        val base = mqttGateway.base
        if (base != null) {
            mqttManager.subscribe("$base/cmd") { _, payload ->
                handler.post { mqttGateway.onCommand(payload) }
            }
        }
    }

    /** Gateway topics follow the brush address; a new brush needs a new last will. */
    @SuppressLint("MissingPermission")
    private fun attachMqttGateway(device: BluetoothDevice) {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        val oldBase = mqttGateway.base
        if (!mqttGateway.attach(device.address, name)) return
        log("MQTT GATEWAY: topics ${mqttGateway.base}/...")
        if (oldBase != null) mqttManager.unsubscribe("$oldBase/cmd")

        val config = mqttManager.loadConfig()
        if (config.enabled && config.host.isNotBlank()) {
            connectMqtt(config)
        }
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

        // The gateway topics (sonicare_gateway/<address>/...) are always
        // published. The older topics under the base topic, with this app's
        // own Home Assistant discovery, only on request.
        val legacyCheckBox = CheckBox(this)
        legacyCheckBox.text = "Own HA discovery + old topics (base topic)"
        legacyCheckBox.isChecked = config.legacyDiscovery
        container.addView(legacyCheckBox)

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
                        tls = tlsCheckBox.isChecked,
                        legacyDiscovery = legacyCheckBox.isChecked
                    )

                    mqttManager.saveConfig(newConfig)

                    if (newConfig.enabled) {
                        updateStatus("Připojuji MQTT...")
                        connectMqtt(newConfig)
                    } else {
                        mqttManager.disconnect()
                        updateStatus("MQTT vypnuto")
                    }

                    log(
                        "MQTT config saved: enabled=${newConfig.enabled}, " +
                            "broker=${newConfig.host}:${newConfig.port}, " +
                            "baseTopic=${newConfig.baseTopic}, tls=${newConfig.tls}, " +
                            "legacyDiscovery=${newConfig.legacyDiscovery}"
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

    private fun startBleForegroundServiceIfReady() {
        if (!blePermissionsGranted()) {
            log("FOREGROUND SERVICE: waiting for Bluetooth permissions")
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            log(
                "FOREGROUND SERVICE: POST_NOTIFICATIONS not granted; " +
                    "gateway will still run, but Android may hide its drawer notification"
            )
        }

        val serviceIntent = SonicareBleForegroundService.startIntent(this)

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            log(
                "FOREGROUND SERVICE: connectedDevice start requested " +
                    "(Activity is visible)"
            )
        } catch (e: Exception) {
            log(
                "FOREGROUND SERVICE START FAILED: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            updateStatus("Nepodařilo se spustit BLE foreground service")
            return
        }

        if (!isBleServiceBound && !isBleServiceBinding) {
            try {
                isBleServiceBinding = true
                val bound = bindService(
                    serviceIntent,
                    bleServiceConnection,
                    Context.BIND_AUTO_CREATE
                )
                if (!bound) {
                    isBleServiceBinding = false
                    log("FOREGROUND SERVICE: bindService returned false")
                    updateStatus("Nepodařilo se připojit k BLE službě")
                } else {
                    log("FOREGROUND SERVICE: binding requested")
                }
            } catch (e: Exception) {
                isBleServiceBinding = false
                log(
                    "FOREGROUND SERVICE BIND FAILED: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                updateStatus("Nepodařilo se připojit k BLE službě")
            }
        }
    }

    private fun requestBlePermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

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
                startBleForegroundServiceIfReady()
                if (autoConnectEnabled) {
                    log("AUTO: permissions ready -> waiting for foreground service binder")
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

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0L)
            .build()

        log(
                "SCAN settings: LOW_POWER, reportDelay=0ms, " +
                    "duration=${scanDurationMs}ms; pause=${scanPauseDurationMs}ms; " +
                    "bonded fallback enabled"
            )

        val service = bleService
        if (service == null) {
            isScanning = false
            log("SCAN: cannot start; foreground service is not bound")
            updateStatus("BLE služba není připojena")
            return
        }

        if (!service.startBleScan(scanSettings, scanCallback)) {
            isScanning = false
            log("SCAN: foreground service could not start BLE scan")
            updateStatus("Nepodařilo se spustit BLE skenování")
            return
        }

        isScanning = true

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

        try {
            bleService?.stopBleScan(scanCallback)
        } catch (e: Exception) {
            log("SCAN stop exception: ${e.message}")
        }

        isScanning = false

        log("=== BLE SCAN STOP ===")
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice(autoRetry: Boolean = false) {

        // BLE scan callbacks may arrive off the main thread. Keep connection state
        // and all subsequent GATT state transitions on the same main looper.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { connectSelectedDevice(autoRetry) }
            return
        }

        if (!autoRetry) {
            manualDisconnectRequested = false
        }

        val device = selectedDevice

        if (device == null) {
            log("CONNECT: no selected device")
            updateStatus("Nejdříve spusť Scan")
            return
        }

        if (gattConnectInProgress) {
            log("CONNECT: connection already in progress - ignoring second request")
            return
        }

        if (bluetoothGatt != null) {
            log("CONNECT: GATT already exists - ignoring second request")
            return
        }

        // A manual Connect starts a fresh retry budget.
        // Automatic retry must keep the already-used budget.
        if (!autoRetry) {
            condorEarlyConnectRetryUsed = false
        }

        // Make sure BLE scanning is stopped before opening GATT.
        stopScan()

        gattConnectInProgress = true
        gattConnectionGeneration++
        val generation = gattConnectionGeneration

        attachMqttGateway(device)

        log("")
        log("=== GATT CONNECT ===")
        log("Address: ${device.address}")
        log("Connection generation=$generation")

        try {
            val service = bleService
                ?: throw IllegalStateException("Foreground BLE service is not bound")

            service.setGattCallbackDelegate(gattCallback)
            val newGatt = service.connectGatt(device)
                ?: throw IllegalStateException("Service returned no BluetoothGatt")

            bluetoothGatt = newGatt

            log(
                "GATT object created: id=${System.identityHashCode(newGatt)}"
            )
            updateStatus("Připojuji...")
        } catch (e: Exception) {
            log("CONNECT EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            gattConnectInProgress = false
            bluetoothGatt = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {

        log("GATT: disconnect requested")
        manualDisconnectRequested = true
        handler.removeCallbacks(autoScanRestartRunnable)

        gattConnectInProgress = false

        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            log("DISCONNECT exception: ${e.message}")
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(
                    gatt,
                    status,
                    newState
                )

                log(
                    "GATT callback id=${System.identityHashCode(gatt)} " +
                        "state=$newState status=$status " +
                        "(${gattStatusName(status)})"
                )
                condorDiagLastGattCallbackAtMs = SystemClock.elapsedRealtime()
                condorDiagLastGattCallbackStatus = status
                condorDiagLastGattCallbackState = newState
                recordCondorGattHistory(
                    "CONNECTION_STATE id=${System.identityHashCode(gatt)} " +
                        "state=$newState status=$status " +
                        "(${gattStatusName(status)})"
                )

                // Ignore callbacks from an old GATT object after a newer
                // connection has already replaced it.
                if (
                    bluetoothGatt != null &&
                    bluetoothGatt !== gatt
                ) {
                    log(
                        "GATT: stale callback from old connection object " +
                            "id=${System.identityHashCode(gatt)}"
                    )

                    if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }

                    return
                }

                if (
                    newState ==
                    BluetoothGatt.STATE_CONNECTED
                ) {

                    gattConnectInProgress = false
                    isGattConnected = true
                    log(
                        "GATT CONNECTED id=${System.identityHashCode(gatt)}"
                    )

                    // Start every GATT session with clean Condor state.
                    condorStage = 0
                    condorPendingRequest = CondorPendingRequest.NONE
                    condorDiscoveryActive = false
                    condorRxFrameBuffer = ByteArray(0)
                    resetCondorTxNotificationTracking()
                    resetCondorGattDiagnostics()
                    condorGattWriteBusyCount = 0
                    condorNextTxSeq = 1
                    resetCondorTxWriteQueue()
                    condorProductIds.clear()
                    condorPorts.clear()
                    condorProductIndex = 0
                    condorPortIndex = 0
                    condorCurrentProductId = null
                    condorCurrentPort = null
                    condorDiscoveredProps.clear()

                    updateStatus("Připojeno")

                    handler.postDelayed({

                        if (
                            bluetoothGatt != gatt ||
                            !isGattConnected
                        ) {
                            log(
                                "GATT: service discovery skipped; connection is gone"
                            )
                            return@postDelayed
                        }

                        log(
                            "GATT: starting service discovery"
                        )

                        try {
                            gatt.discoverServices()
                        } catch (e: Exception) {
                            log(
                                "discoverServices exception: " +
                                    "${e.message}"
                            )
                        }

                    }, 300)

                } else if (
                    newState ==
                    BluetoothGatt.STATE_DISCONNECTED
                ) {

                    log("GATT DISCONNECTED")
                    log(
                        "GATT disconnect status=$status " +
                            "(${gattStatusName(status)})"
                    )
                    log(
                        "CONDOR stage at disconnect=$condorStage"
                    )

                    val disconnectIdleMs =
                        if (condorLastActivityAtMs == 0L) {
                            -1L
                        } else {
                            SystemClock.elapsedRealtime() - condorLastActivityAtMs
                        }

                    log(
                        "CONDOR last activity before disconnect: " +
                            "${if (disconnectIdleMs >= 0) "${disconnectIdleMs} ms ago" else "unknown"}"
                    )

                    log("")
                    log("=== CONDOR DISCONNECT DIAGNOSTICS v0.1.30 ===")
                    fun diagAge(atMs: Long): String =
                        if (atMs == 0L) "never" else "${SystemClock.elapsedRealtime() - atMs} ms ago"
                    log("last BLE notification: ${diagAge(condorDiagLastBleNotificationAtMs)} uuid=${condorDiagLastBleNotificationUuid.ifBlank { "none" }}")
                    log("last Condor TX notification: ${diagAge(condorDiagLastTxNotificationAtMs)} hex=${condorDiagLastTxNotificationHex.ifBlank { "none" }}")
                    log("last ChangeIndication: ${diagAge(condorDiagLastChangeIndicationAtMs)} hex=${condorDiagLastChangeIndicationHex.ifBlank { "none" }}")
                    log("last ChangeIndication ACK queued: ${diagAge(condorDiagLastChangeIndicationAckAtMs)}")
                    log("last GenericResp: ${diagAge(condorDiagLastGenericRespAtMs)} ${condorDiagLastGenericRespSummary}")
                    log("last successful GATT write: ${diagAge(condorDiagLastSuccessfulGattWriteAtMs)} ${condorDiagLastSuccessfulGattWriteDescription}")
                    log("last GATT BUSY 201: ${diagAge(condorDiagLastGattBusyAtMs)} ${condorDiagLastGattBusyDescription}; totalBusy=$condorDiagGattBusyTotal")
                    log("last GATT callback: ${diagAge(condorDiagLastGattCallbackAtMs)} state=$condorDiagLastGattCallbackState status=$condorDiagLastGattCallbackStatus")
                    log("pendingRequest=$condorPendingRequest; keepAliveCount=$condorIdleKeepAliveCount; keepAlivePending=$condorIdleKeepAlivePending")
                    log("TX active=${condorTxActiveFrameDescription.ifBlank { "none" }}; activeUrgent=$condorTxActiveFrameIsUrgent; txOffset=$condorTxActiveOffset; urgentTxQueue=${condorUrgentTxFrameQueue.size}; txQueue=${condorTxFrameQueue.size}; gattQueue=${condorGattWriteQueue.size}; gattInFlight=${condorGattWriteInFlight?.description ?: "none"}")
                    log("txNextSeq=${condorNextTxSeq and CondorProtocol.SEQ_MASK}; rxBuffer=${condorRxFrameBuffer.size} bytes")
                    dumpCondorGattHistory()

                    isGattConnected = false
                    gattConnectInProgress = false
                    condorDiscoveryActive = false
                    if (condorPendingRequest == CondorPendingRequest.GATEWAY_CMD) {
                        condorPendingRequest = CondorPendingRequest.NONE
                    }
                    mqttGateway.sessionDown()
                    condorIdleWatchdogRunning = false
                    handler.removeCallbacks(condorIdleWatchdogRunnable)
                    handler.removeCallbacks(condorIdleKeepAliveRunnable)
                    condorIdleKeepAlivePending = false
                    condorLastActivityAtMs = 0L
                    resetCondorTxNotificationTracking()
                    resetCondorTxWriteQueue()
                    updateStatus("Odpojeno")

                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }

                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        log("GATT close exception: ${e.message}")
                    }

                    // If this was a normal/idle disconnect, return to BLE scan
                    // mode automatically. The special early GATT_ERROR retry
                    // below gets priority and prevents two reconnect paths from
                    // running at the same time. A user-requested disconnect
                    // does not restart scanning.
                    if (
                        autoConnectEnabled &&
                        !manualDisconnectRequested &&
                        !(status == 257 && condorStage <= 3 && !condorEarlyConnectRetryUsed)
                    ) {
                        handler.removeCallbacks(autoScanRestartRunnable)
                        log("AUTO: GATT disconnected -> scan again in ${autoScanRetryDelayMs} ms")
                        handler.postDelayed(autoScanRestartRunnable, autoScanRetryDelayMs)
                    }

                    // The first connection can occasionally die with GATT_ERROR
                    // immediately after the early Condor CLIENT_CFG write.
                    // Retry only once, and only while still in the early
                    // handshake, so a real later disconnect is not hidden
                    // behind an automatic reconnect loop.
                    if (
                        status == 257 &&
                        condorStage <= 3 &&
                        !condorEarlyConnectRetryUsed &&
                        selectedDevice != null
                    ) {
                        condorEarlyConnectRetryUsed = true
                        val retryDevice = selectedDevice

                        log("")
                        log("=== CONDOR EARLY GATT RETRY ===")
                        log("Reason: GATT_ERROR 257 during early Condor handshake")
                        log("Condor stage=$condorStage")
                        log("Waiting ${condorEarlyConnectRetryDelayMs} ms before retry")

                        handler.postDelayed({
                            if (
                                retryDevice != null &&
                                bluetoothGatt == null &&
                                !isGattConnected &&
                                !gattConnectInProgress
                            ) {
                                selectedDevice = retryDevice
                                log("CONDOR retry: starting second GATT connection")
                                connectSelectedDevice(autoRetry = true)
                            } else {
                                log("CONDOR retry: skipped; GATT state changed")
                            }
                        }, condorEarlyConnectRetryDelayMs)
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                super.onServicesDiscovered(
                    gatt,
                    status
                )

                log("")
                log("=== SERVICES DISCOVERED ===")

                log(
                    "status=$status " +
                        "(${gattStatusName(status)})"
                )

                if (
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    log(
                        "SERVICE DISCOVERY FAILED"
                    )
                    return
                }

                dumpGattServices(gatt)

                log("")
                log(
                    "=== STANDARD GATT READS SKIPPED ==="
                )
                log(
                    "Skipping ordinary GATT reads during Condor handshake " +
                        "to avoid competing asynchronous ATT operations."
                )

                handler.postDelayed({

                    if (
                        bluetoothGatt == gatt &&
                        isGattConnected
                    ) {
                        enableServerCfgNotify(gatt)
                    } else {
                        log(
                            "SERVER_CFG setup skipped: GATT is no longer connected"
                        )
                    }

                }, 500)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(
                    gatt,
                    characteristic,
                    value,
                    status
                )

                log(
                    "READ RESULT " +
                        "${characteristic.uuid}: " +
                        "status=$status " +
                        "(${gattStatusName(status)})"
                )

                if (
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    log(
                        "READ HEX: ${bytesToHex(value)}"
                    )

                    val ascii = bytesToAscii(value)

                    if (ascii.isNotBlank()) {
                        log(
                            "READ ASCII: $ascii"
                        )
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                super.onDescriptorWrite(
                    gatt,
                    descriptor,
                    status
                )

                log(
                    "DESCRIPTOR WRITE " +
                        "${descriptor.characteristic.uuid}: " +
                        "status=$status " +
                        "(${gattStatusName(status)})"
                )

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(
                        "NOTIFY DESCRIPTOR FAILED: " +
                            "${descriptor.characteristic.uuid}"
                    )
                    return
                }

                when (descriptor.characteristic.uuid) {

                    CHAR_SERVER_CFG_UUID -> {
                        log("SERVER_CFG notify ENABLED")
                        condorStage = 1

                        handler.postDelayed({
                            if (
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                attemptCondorVersionWrite(gatt)
                            }
                        }, 100)
                    }

                    CHAR_NOTIFY_TX_UUID -> {
                        log("TX notify ENABLED")
                        condorStage = 2

                        handler.postDelayed({
                            if (
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                enableCondorNotify(
                                    gatt,
                                    CHAR_NOTIFY_RX_UUID,
                                    "RX_ACK"
                                )
                            }
                        }, 100)
                    }

                    CHAR_NOTIFY_RX_UUID -> {
                        log("RX_ACK notify ENABLED")
                        condorStage = 3

                        handler.postDelayed({
                            if (
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                attemptCondorClientCfgWrite(
                                    gatt,
                                    byteArrayOf(
                                        0xFF.toByte(),
                                        0xFF.toByte(),
                                        0xFF.toByte(),
                                        0xFF.toByte()
                                    ),
                                    "FF FF FF FF"
                                )
                            }
                        }, 100)
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                super.onCharacteristicWrite(
                    gatt,
                    characteristic,
                    status
                )

                log("")
                log(
                    "=== CHARACTERISTIC WRITE CALLBACK ==="
                )

                log(
                    "UUID=${characteristic.uuid}"
                )

                log(
                    "status=$status " +
                        "(${gattStatusName(status)})"
                )

                log(
                    "writeType=${characteristic.writeType}"
                )

                log(
                    "properties=${characteristic.properties} " +
                        "(${propertiesToString(characteristic.properties)})"
                )

                val writtenValue =
                    try {
                        characteristic.value
                    } catch (_: Exception) {
                        null
                    }

                if (writtenValue != null) {
                    log(
                        "VALUE HEX: " +
                            bytesToHex(writtenValue)
                    )

                    val ascii =
                        bytesToAscii(writtenValue)

                    if (ascii.isNotBlank()) {
                        log(
                            "VALUE ASCII: $ascii"
                        )
                    }
                }

                if (
                    characteristic.uuid == CHAR_WRITE_NR_UUID ||
                    characteristic.uuid == CHAR_WRITE_CFG_UUID
                ) {
                    handleCondorGattWriteCallback(
                        gatt,
                        characteristic.uuid,
                        status
                    )
                }

                if (
                    characteristic.uuid ==
                    CHAR_CLIENT_CFG_UUID
                ) {

                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    ) {
                        log(
                            "CONDOR CLIENT_CFG WRITE OK"
                        )

                        if (condorStage == 3) {
                            condorStage = 4

                            log(
                                "CONDOR: waiting for 6-byte " +
                                    "SERVER_CFG channel configuration"
                            )
                        }
                    } else {
                        log(
                            "CONDOR CLIENT_CFG WRITE FAILED"
                        )
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(
                    gatt,
                    characteristic,
                    value
                )

                log("")
                condorLastActivityAtMs = SystemClock.elapsedRealtime()

                log(
                    "=== NOTIFICATION ==="
                )

                log(
                    "UUID=${characteristic.uuid}"
                )

                log(
                    "HEX=${bytesToHex(value)}"
                )

                val ascii =
                    bytesToAscii(value)

                if (ascii.isNotBlank()) {
                    log(
                        "ASCII=$ascii"
                    )
                }

                if (
                    characteristic.uuid ==
                    CHAR_SERVER_CFG_UUID
                ) {
                    log(
                        "SERVER_CFG notification received"
                    )

                    analyzeServerCfg(value)

                    if (
                        value.size == 1 &&
                        (value[0].toInt() and 0xFF) == 4 &&
                        condorStage == 1
                    ) {
                        log(
                            "CONDOR V4 confirmed -> TX notify scheduled after 500 ms"
                        )

                        log(
                            "TX notify timing: waiting 500 ms after SERVER_CFG=04"
                        )

                        handler.postDelayed({
                            log(
                                "TX notify delay elapsed; checking GATT before enable"
                            )

                            log(
                                "TX notify GATT check: same=${bluetoothGatt === gatt}, " +
                                    "connected=$isGattConnected, gattId=${System.identityHashCode(gatt)}"
                            )

                            if (
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                log(
                                    "TX notify: calling enableCondorNotify()"
                                )

                                enableCondorNotify(
                                    gatt,
                                    CHAR_NOTIFY_TX_UUID,
                                    "TX"
                                )
                            } else {
                                log(
                                    "TX notify ABORTED: GATT changed or disconnected"
                                )
                            }
                        }, 500)
                    }

                    if (
                        value.size == 6 &&
                        condorStage == 4
                    ) {
                        log(
                            "CONDOR: 6-byte channel configuration received"
                        )

                        log(
                            "CHANNEL CFG HEX: ${bytesToHex(value)}"
                        )

                        condorStage = 5

                        handler.postDelayed({
                            if (
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                attemptCondorOpenRx(gatt)
                            }
                        }, 100)
                    }
                }

                if (
                    characteristic.uuid ==
                    CHAR_NOTIFY_TX_UUID
                ) {
                    handleCondorTxNotification(gatt, value)
                }

                if (
                    characteristic.uuid ==
                    CHAR_NOTIFY_RX_UUID &&
                    condorStage == 6
                ) {
                    log(
                        "CONDOR RX_ACK notification received"
                    )

                    log(
                        "CONDOR V4 handshake reached RX_ACK"
                    )

                    condorStage = 7
                    updateStatus("Condor V4 handshake OK")

                    handler.postDelayed({
                        if (
                            bluetoothGatt == gatt &&
                            isGattConnected
                        ) {
                            attemptCondorInitializeReq(gatt)
                        }
                    }, 100)
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun dumpGattServices(
        gatt: BluetoothGatt
    ) {

        for (service in gatt.services) {

            log(
                "SERVICE: ${service.uuid}"
            )

            for (
                characteristic
                in service.characteristics
            ) {

                log(
                    "  CHAR: ${characteristic.uuid}"
                )

                log(
                    "    properties=" +
                        characteristic.properties +
                        " (" +
                        propertiesToString(
                            characteristic.properties
                        ) +
                        ")"
                )

                log(
                    "    writeType=" +
                        characteristic.writeType
                )

                for (
                    descriptor
                    in characteristic.descriptors
                ) {

                    log(
                        "    DESC: ${descriptor.uuid}"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readStandardCharacteristics(
        gatt: BluetoothGatt
    ) {

        val deviceInfo =
            gatt.getService(
                DEVICE_INFORMATION_SERVICE_UUID
            )

        if (deviceInfo == null) {
            log(
                "Device Information service not found"
            )
            return
        }

        log(
            "Device Information service found"
        )

        val characteristics =
            deviceInfo.characteristics

        for (characteristic in characteristics) {

            if (
                characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_READ != 0
            ) {

                log(
                    "READ REQUEST: " +
                        characteristic.uuid
                )

                try {
                    gatt.readCharacteristic(
                        characteristic
                    )

                    /*
                     * We intentionally don't fire all reads
                     * at once. Android BLE operations need to
                     * be serialized.
                     *
                     * For this diagnostic version we only start
                     * the first readable characteristic here.
                     */
                    break

                } catch (e: Exception) {

                    log(
                        "READ exception: " +
                            "${e.message}"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableServerCfgNotify(
        gatt: BluetoothGatt
    ) {

        log("")
        log(
            "=== ENABLE SERVER_CFG NOTIFY ==="
        )

        val service =
            gatt.getService(
                CONDOR_SERVICE_UUID
            )

        if (service == null) {

            log(
                "CONDOR service NOT FOUND"
            )

            return
        }

        val characteristic =
            service.getCharacteristic(
                CHAR_SERVER_CFG_UUID
            )

        if (characteristic == null) {

            log(
                "SERVER_CFG characteristic NOT FOUND"
            )

            return
        }

        val cccd =
            characteristic.getDescriptor(
                CCCD_UUID
            )

        if (cccd == null) {

            log(
                "SERVER_CFG CCCD NOT FOUND"
            )

            return
        }

        try {

            val localResult =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            log(
                "setCharacteristicNotification=" +
                    localResult
            )

            val value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            if (Build.VERSION.SDK_INT >= 33) {

                val result =
                    gatt.writeDescriptor(
                        cccd,
                        value
                    )

                log(
                    "writeDescriptor API33+ immediateResult=$result " +
                        "(${bluetoothStatusCodeName(result)})"
                )

                if (
                    result != BluetoothGatt.GATT_SUCCESS
                ) {
                    log(
                        "ERROR: CCCD write was not accepted by Android " +
                            "(immediate status=$result " +
                            "${bluetoothStatusCodeName(result)})"
                    )
                }

            } else {

                @Suppress("DEPRECATION")
                cccd.value = value

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeDescriptor(cccd)

                log(
                    "writeDescriptor result=$result"
                )
            }

        } catch (e: Exception) {

            log(
                "ENABLE NOTIFY exception: " +
                    "${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableCondorNotify(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        label: String
    ) {
        log("")
        log("=== ENABLE $label NOTIFY ===")

        val service = gatt.getService(CONDOR_SERVICE_UUID)

        if (service == null) {
            log("CONDOR service NOT FOUND")
            return
        }

        val characteristic =
            service.getCharacteristic(characteristicUuid)

        if (characteristic == null) {
            log("$label characteristic NOT FOUND")
            return
        }

        val cccd =
            characteristic.getDescriptor(CCCD_UUID)

        if (cccd == null) {
            log("$label CCCD NOT FOUND")
            return
        }

        try {
            val localResult =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            log(
                "setCharacteristicNotification($label)=" +
                    localResult +
                    " gattId=${System.identityHashCode(gatt)}"
            )

            log(
                "CCCD $label: uuid=${cccd.uuid}, currentValue=" +
                    try { bytesToHex(cccd.value ?: ByteArray(0)) } catch (_: Exception) { "<unavailable>" }
            )

            val value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            if (Build.VERSION.SDK_INT >= 33) {
                val result =
                    gatt.writeDescriptor(
                        cccd,
                        value
                    )

                log(
                    "writeDescriptor API33+ $label: result=$result " +
                        "(${bluetoothStatusCodeName(result)}), " +
                        "value=${bytesToHex(value)}, gattId=${System.identityHashCode(gatt)}"
                )
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeDescriptor(cccd)

                log(
                    "writeDescriptor $label result=$result"
                )
            }

        } catch (e: Exception) {
            log(
                "ENABLE $label NOTIFY exception: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorClientCfgWrite(
        gatt: BluetoothGatt,
        value: ByteArray,
        description: String
    ) {
        if (
            bluetoothGatt != gatt ||
            !isGattConnected
        ) {
            log("CLIENT_CFG write skipped: GATT is no longer connected")
            return
        }
        log("")
        log("=== CONDOR CLIENT_CFG WRITE ===")
        log("Target: $CHAR_CLIENT_CFG_UUID")
        log("Value: $description")

        val service =
            gatt.getService(CONDOR_SERVICE_UUID)

        if (service == null) {
            log("CONDOR service NOT FOUND")
            return
        }

        val characteristic =
            service.getCharacteristic(CHAR_CLIENT_CFG_UUID)

        if (characteristic == null) {
            log("CLIENT_CFG characteristic NOT FOUND")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        value,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    )

                log(
                    "writeCharacteristic API33+ " +
                        "returned=$result (${bluetoothStatusCodeName(result)})"
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value

                characteristic.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeCharacteristic(
                        characteristic
                    )

                log(
                    "writeCharacteristic returned=$result"
                )
            }

        } catch (e: Exception) {
            log(
                "CLIENT_CFG WRITE EXCEPTION: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorOpenRx(
        gatt: BluetoothGatt
    ) {
        if (
            bluetoothGatt != gatt ||
            !isGattConnected
        ) {
            log("RX open skipped: GATT is no longer connected")
            return
        }
        log("")
        log("=== CONDOR OPEN RX CHANNEL ===")
        log("Target: $CHAR_WRITE_NR_UUID")
        log("Value: 40")

        val service =
            gatt.getService(CONDOR_SERVICE_UUID)

        if (service == null) {
            log("CONDOR service NOT FOUND")
            return
        }

        val characteristic =
            service.getCharacteristic(CHAR_WRITE_NR_UUID)

        if (characteristic == null) {
            log("RX WRITE characteristic NOT FOUND")
            return
        }

        val value =
            byteArrayOf(0x40)

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        value,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    )

                log(
                    "writeCharacteristic API33+ " +
                        "returned=$result (${bluetoothStatusCodeName(result)})"
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value

                characteristic.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeCharacteristic(
                        characteristic
                    )

                log(
                    "writeCharacteristic returned=$result"
                )
            }

            condorStage = 6
            log(
                "CONDOR: RX channel opened, " +
                    "waiting for RX_ACK"
            )

        } catch (e: Exception) {
            log(
                "OPEN RX WRITE EXCEPTION: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorInitializeReq(
        gatt: BluetoothGatt
    ) {
        if (
            bluetoothGatt != gatt ||
            !isGattConnected
        ) {
            log("InitializeReq skipped: GATT is no longer connected")
            return
        }

        if (condorStage < 7) {
            log("CONDOR: InitializeReq skipped, handshake not complete")
            return
        }

        log("")
        log("=== CONDOR INITIALIZE REQUEST ===")

        val frame = CondorProtocol.initializeReq()

        log("Frame HEX: ${bytesToHex(frame)}")
        log("Frame type: 1 (InitializeReq)")
        log("Frame length: 0")
        log("Transport sequence start: $condorNextTxSeq")

        sendCondorFrame(
            gatt,
            frame
        )

        condorStage = 8
        updateStatus("Condor InitializeReq odeslán")
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorGetProducts(
        gatt: BluetoothGatt
    ) {
        if (condorStage < 8) {
            log("CONDOR: GetProducts skipped, InitializeReq sequence not complete")
            return
        }

        condorPendingRequest = CondorPendingRequest.GET_PRODUCTS
        condorDiscoveryActive = true
        condorProductIds.clear()
        condorPorts.clear()
        condorProductIndex = 0
        condorPortIndex = 0
        condorCurrentProductId = null
        condorCurrentPort = null
        condorDiscoveredProps.clear()

        log("")
        log("=== CONDOR GET PRODUCTS REQUEST ===")
        log("Starting automatic product -> port -> props discovery")
        log("Message type: 10 (GetProducts)")
        log("Body length: 0")

        val frame = CondorProtocol.getProductsReq()

        log("Frame HEX: ${bytesToHex(frame)}")
        log("Transport sequence start: $condorNextTxSeq")

        sendCondorFrame(gatt, frame)
        updateStatus("Condor GetProducts odeslán")
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorGetPorts(
        gatt: BluetoothGatt,
        productId: String
    ) {
        condorPendingRequest = CondorPendingRequest.GET_PORTS
        condorCurrentProductId = productId

        val body = productId.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        val frame = CondorProtocol.getPortsReq(productId)

        log("")
        log("=== CONDOR GET PORTS REQUEST ===")
        log("Message type: 11 (GetPorts)")
        log("Product ID: $productId")
        log("Body length: ${body.size}")
        log("Body HEX: ${bytesToHex(body)}")
        log("Frame HEX: ${bytesToHex(frame)}")
        log("Transport sequence start: $condorNextTxSeq")

        sendCondorFrame(gatt, frame)
        updateStatus("Condor GetPorts: $productId")
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorGetProps(
        gatt: BluetoothGatt,
        productId: String,
        port: String
    ) {
        condorPendingRequest = CondorPendingRequest.GET_PROPS
        condorCurrentProductId = productId
        condorCurrentPort = port

        val body =
            productId.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) +
                port.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00)

        val frame = CondorProtocol.getPropsReq(productId, port)

        log("")
        log("=== CONDOR GET PROPS REQUEST ===")
        log("Message type: 4 (GetProps)")
        log("Product ID: $productId")
        log("Port: $port")
        log("Body length: ${body.size}")
        log("Body HEX: ${bytesToHex(body)}")
        log("Frame HEX: ${bytesToHex(frame)}")
        log("GET_PROPS PAYLOAD HEX: ${bytesToHex(body)}")
        log("GET_PROPS FRAME HEX: ${bytesToHex(frame)}")
        log("Transport sequence start: $condorNextTxSeq")

        sendCondorFrame(gatt, frame)
        updateStatus("Condor GetProps: $productId / $port")
    }


    @SuppressLint("MissingPermission")
    private fun sendCondorFrame(
        gatt: BluetoothGatt,
        frame: ByteArray,
        urgent: Boolean = false,
        description: String = "Condor type ${if (frame.size > 2) frame[2].toInt() and 0xFF else -1}"
    ) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR FRAME WRITE skipped: GATT is no longer connected")
            return
        }

        val service = gatt.getService(CONDOR_SERVICE_UUID)
        if (service == null) {
            log("CONDOR service NOT FOUND")
            return
        }

        if (service.getCharacteristic(CHAR_WRITE_NR_UUID) == null) {
            log("CONDOR RX WRITE characteristic NOT FOUND")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (urgent) {
            condorUrgentTxFrameQueue.addLast(
                CondorUrgentTxFrame(
                    value = frame.copyOf(),
                    description = description,
                    enqueuedAtElapsedMs = now
                )
            )
        } else {
            condorTxFrameQueue.add(frame.copyOf())
        }

        log(
            "CONDOR TX frame queued: ${frame.size} bytes; " +
                "BLE fragments=${((frame.size + 18) / 19)}; " +
                "urgent=$urgent; description=$description; " +
                "urgentQueue=${condorUrgentTxFrameQueue.size}; " +
                "normalQueue=${condorTxFrameQueue.size}; " +
                "writeInFlight=$condorTxWriteInFlight; " +
                "GATT queue=${condorGattWriteQueue.size}"
        )

        if (
            !condorTxWriteInFlight &&
            condorTxActiveFrame == null &&
            condorGattWriteQueue.isEmpty()
        ) {
            startNextCondorTxFrame(gatt)
        } else {
            pumpCondorGattWriteQueue(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNextCondorTxFrame(
        gatt: BluetoothGatt
    ) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR TX: cannot start frame, GATT is disconnected")
            return
        }

        if (condorTxWriteInFlight) return

        if (condorTxActiveFrame == null) {
            if (condorUrgentTxFrameQueue.isNotEmpty()) {
                val urgentFrame = condorUrgentTxFrameQueue.removeFirst()
                condorTxActiveFrame = urgentFrame.value
                condorTxActiveFrameIsUrgent = true
                condorTxActiveFrameDescription = urgentFrame.description
                condorTxActiveFrameEnqueuedAtMs = urgentFrame.enqueuedAtElapsedMs
            } else {
                if (condorTxFrameQueue.isEmpty()) return
                condorTxActiveFrame = condorTxFrameQueue.removeFirst()
                condorTxActiveFrameIsUrgent = false
                condorTxActiveFrameDescription = "normal Condor frame"
                condorTxActiveFrameEnqueuedAtMs = SystemClock.elapsedRealtime()
            }

            condorTxActiveOffset = 0
            val queueWaitMs =
                SystemClock.elapsedRealtime() - condorTxActiveFrameEnqueuedAtMs

            log(
                "CONDOR TX: starting queued frame; " +
                    "description=$condorTxActiveFrameDescription; " +
                    "urgent=$condorTxActiveFrameIsUrgent; " +
                    "queueWait=${queueWaitMs}ms; " +
                    "bytes=${condorTxActiveFrame!!.size}; " +
                    "urgentQueue=${condorUrgentTxFrameQueue.size}; " +
                    "normalQueue=${condorTxFrameQueue.size}"
            )
        }

        sendNextCondorTxFragment(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextCondorTxFragment(
        gatt: BluetoothGatt
    ) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR TX: fragment send aborted, GATT is disconnected")
            return
        }

        if (condorTxWriteInFlight) {
            log("CONDOR TX: fragment send skipped, another GATT write is in flight")
            return
        }

        if (condorGattWriteQueue.isNotEmpty()) {
            log(
                "CONDOR TX: fragment not added; GATT queue has " +
                    "${condorGattWriteQueue.size} item(s)"
            )
            return
        }

        val frame = condorTxActiveFrame
        if (frame == null) {
            startNextCondorTxFrame(gatt)
            return
        }

        if (condorTxActiveOffset >= frame.size) {
            log("CONDOR TX: frame completely sent")
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            startNextCondorTxFrame(gatt)
            return
        }

        val service = gatt.getService(CONDOR_SERVICE_UUID)
        val characteristic =
            service?.getCharacteristic(CHAR_WRITE_NR_UUID)

        if (characteristic == null) {
            log("CONDOR RX WRITE characteristic NOT FOUND")
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
            return
        }

        val maxBleWrite = 20
        val chunkPayloadSize = maxBleWrite - 1
        val end = minOf(
            condorTxActiveOffset + chunkPayloadSize,
            frame.size
        )

        val chunk =
            frame.copyOfRange(
                condorTxActiveOffset,
                end
            )

        // Do NOT advance seq/offset here. Android has not confirmed the write
        // yet. They advance only from onCharacteristicWrite(GATT_SUCCESS).
        val seq =
            condorNextTxSeq and CondorProtocol.SEQ_MASK

        val packet =
            byteArrayOf(seq.toByte()) + chunk

        val op = CondorGattWriteOp(
            kind = CondorGattWriteKind.RX_FRAME,
            characteristicUuid = CHAR_WRITE_NR_UUID,
            value = packet,
            description =
                "RX_FRAME ${condorTxActiveFrameDescription} " +
                    "urgent=$condorTxActiveFrameIsUrgent " +
                    "queueWait=${SystemClock.elapsedRealtime() - condorTxActiveFrameEnqueuedAtMs}ms " +
                    "seq=$seq offset=$condorTxActiveOffset..${end - 1} " +
                    "${end}/${frame.size}",
            frameEndOffset = end,
            frameSeq = seq
        )

        log(
            "CONDOR RX TX packet queued: ${op.description} " +
                "HEX=${bytesToHex(packet)}"
        )

        // TX_ACK packets have strict priority over queued RX_FRAME packets.
        // This prevents a pending application frame from delaying an ACK.
        if (op.kind == CondorGattWriteKind.TX_ACK) {
            val firstRxFrame = condorGattWriteQueue.indexOfFirst {
                it.kind == CondorGattWriteKind.RX_FRAME
            }

            if (firstRxFrame >= 0) {
                val queueList = condorGattWriteQueue.toMutableList()
                queueList.add(firstRxFrame, op)
                condorGattWriteQueue.clear()
                condorGattWriteQueue.addAll(queueList)
            } else {
                condorGattWriteQueue.addLast(op)
            }
        } else {
            condorGattWriteQueue.addLast(op)
        }

        pumpCondorGattWriteQueue(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun pumpCondorGattWriteQueue(
        gatt: BluetoothGatt
    ) {
        if (condorGattWritePumpRunning) {
            condorGattWritePumpRequested = true
            log("CONDOR GATT WRITE: pump already running; another pass deferred")
            return
        }

        condorGattWritePumpRunning = true
        try {
            if (bluetoothGatt != gatt || !isGattConnected) {
                log("CONDOR GATT WRITE: pump aborted, GATT disconnected")
                return
            }

            if (
                condorGattWriteInFlight != null ||
                condorGattWriteRetryScheduled
            ) {
                return
            }

            val op = condorGattWriteQueue.peekFirst() ?: return

            val service = gatt.getService(CONDOR_SERVICE_UUID)
            val characteristic =
                service?.getCharacteristic(op.characteristicUuid)

            if (characteristic == null) {
                log(
                    "CONDOR GATT WRITE: characteristic not found: " +
                        op.characteristicUuid
                )
                condorGattWriteQueue.removeFirst()
                handleCondorGattWriteFailure(gatt, op, -1)
                return
            }

            log(
                "CONDOR GATT WRITE START: ${op.description}; " +
                    "queue=${condorGattWriteQueue.size}; " +
                    "HEX=${bytesToHex(op.value)}"
            )
            recordCondorGattHistory(
                "WRITE_START ${op.description} queue=${condorGattWriteQueue.size}"
            )

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result =
                        gatt.writeCharacteristic(
                            characteristic,
                            op.value,
                            BluetoothGattCharacteristic
                                .WRITE_TYPE_NO_RESPONSE
                        )

                    log(
                        "CONDOR GATT WRITE immediateResult=$result " +
                            "(${bluetoothStatusCodeName(result)}) op=${op.kind}"
                    )
                    recordCondorGattHistory(
                        "WRITE_RESULT result=$result ${op.description}"
                    )

                    when (result) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            /*
                             * WRITE_NO_RESPONSE is different from a normal
                             * acknowledged GATT write: Android already returned
                             * GATT_SUCCESS, meaning the invocation was accepted.
                             *
                             * On the Pixel + HX741X, onCharacteristicWrite() can
                             * arrive immediately afterwards, but it is not
                             * reliable enough to use as the completion signal for
                             * this WRITE_NO_RESPONSE operation. Waiting for it can
                             * leave the Condor queue stuck for 5 seconds even though
                             * the brush already accepted the packet.
                             *
                             * Therefore GATT_SUCCESS completes this local queue
                             * operation immediately. The 201 path remains strict:
                             * 201 means the invocation was NOT accepted, so the
                             * exact same packet stays at the queue head and is
                             * retried.
                             */
                            condorGattWriteQueue.removeFirst()

                            if (op.kind == CondorGattWriteKind.RX_FRAME) {
                                condorTxWriteInFlight = false

                                condorNextTxSeq =
                                    (condorNextTxSeq + 1) and CondorProtocol.SEQ_MASK

                                condorTxActiveOffset = op.frameEndOffset

                                if (
                                    condorTxActiveFrame != null &&
                                    condorTxActiveOffset >=
                                        condorTxActiveFrame!!.size
                                ) {
                                    log(
                                        "CONDOR TX: complete frame accepted by GATT"
                                    )
                                    condorTxActiveFrame = null
                                    condorTxActiveOffset = 0
                                }
                            }

                            condorDiagLastSuccessfulGattWriteAtMs =
                                SystemClock.elapsedRealtime()
                            condorDiagLastSuccessfulGattWriteDescription =
                                op.description
                            recordCondorGattHistory(
                                "WRITE_ACCEPTED ${op.description} " +
                                    "queue=${condorGattWriteQueue.size}"
                            )

                            log(
                                "CONDOR GATT WRITE ACCEPTED: " +
                                    "${op.description}; WRITE_NO_RESPONSE " +
                                    "completed from immediateResult=0; " +
                                    "activeOffset=$condorTxActiveOffset; " +
                                    "activeFrameSize=${condorTxActiveFrame?.size ?: 0}; " +
                                    "queue=${condorGattWriteQueue.size}"
                            )

                            /*
                             * Do not immediately issue another BLE write after
                             * WRITE_NO_RESPONSE returned 0. The invocation was
                             * accepted, but the Pixel/Android ATT pipeline can
                             * still need a short moment before the next write.
                             *
                             * RX_FRAME continuation is intentionally paced at 100 ms in
                             * v0.1.30, while TX_ACK operations bypass that pacing.
                             *
                             * A late onCharacteristicWrite() callback may still
                             * arrive. It must not consume the next queued operation.
                             */
                            /*
                             * TX_ACK is time-critical. Do not put an ACK behind
                             * the 100 ms RX_FRAME pacing experiment.
                             *
                             * RX_FRAME writes remain deliberately paced to avoid
                             * Android GATT_BUSY (201), while a queued TX_ACK is
                             * dispatched as soon as the current write has been
                             * accepted. This mirrors the upstream Condor rule
                             * that every incoming TX packet must be ACKed promptly.
                             */
                            val nextOp = condorGattWriteQueue.peekFirst()
                            val urgentFrameReady =
                                condorTxActiveFrame == null &&
                                    condorUrgentTxFrameQueue.isNotEmpty() &&
                                    condorGattWriteQueue.isEmpty()

                            if (
                                nextOp?.kind == CondorGattWriteKind.TX_ACK &&
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                log(
                                    "CONDOR GATT WRITE: TX_ACK bypasses " +
                                        "${condorGattWritePacingDelayMs} ms pacing"
                                )
                                pumpCondorGattWriteQueue(gatt)
                            } else if (
                                urgentFrameReady &&
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                scheduleCondorPriorityFrameStart(gatt)
                            } else if (
                                !condorGattWritePacingScheduled &&
                                bluetoothGatt == gatt &&
                                isGattConnected
                            ) {
                                condorGattWritePacingScheduled = true
                                handler.postDelayed(
                                    condorGattWritePacingRunnable,
                                    condorGattWritePacingDelayMs
                                )
                                log(
                                    "CONDOR GATT WRITE: RX_FRAME next operation " +
                                        "paced by ${condorGattWritePacingDelayMs} ms"
                                )
                            }
                        }

                        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                            // 201 is NOT an accepted write. Keep the exact packet
                            // at queue head; do not advance Condor state and do not
                            // wait for a callback belonging to another write.
                            op.retryCount++
                            condorGattWriteBusyCount++
                    condorDiagGattBusyTotal++
                    condorDiagLastGattBusyAtMs = SystemClock.elapsedRealtime()
                    condorDiagLastGattBusyDescription = op.description

                            recordCondorGattHistory(
                                "WRITE_BUSY_201 ${op.description} retry=${op.retryCount}"
                            )

                            log(
                                "CONDOR GATT WRITE BUSY (201): " +
                                    "totalBusy=$condorGattWriteBusyCount; " +
                                    "${op.description}; " +
                                    "retry ${op.retryCount}/$condorGattWriteMaxBusyRetries"
                            )

                            if (
                                op.retryCount > condorGattWriteMaxBusyRetries
                            ) {
                                condorGattWriteQueue.removeFirst()
                                handleCondorGattWriteFailure(
                                    gatt,
                                    op,
                                    BluetoothStatusCodes
                                        .ERROR_GATT_WRITE_REQUEST_BUSY,
                                    bluetoothStatusCodeName(
                                        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                                    )
                                )
                            } else {
                                condorGattWriteRetryScheduled = true
                                handler.postDelayed({
                                    condorGattWriteRetryScheduled = false
                                    if (
                                        bluetoothGatt == gatt &&
                                        isGattConnected
                                    ) {
                                        pumpCondorGattWriteQueue(gatt)
                                    }
                                }, condorGattWriteRetryDelayMs)
                            }
                        }

                        else -> {
                            condorGattWriteQueue.removeFirst()
                            handleCondorGattWriteFailure(
                                gatt,
                                op,
                                result,
                                bluetoothStatusCodeName(result)
                            )
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = op.value
                    characteristic.writeType =
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE

                    @Suppress("DEPRECATION")
                    val accepted =
                        gatt.writeCharacteristic(characteristic)

                    log(
                        "CONDOR GATT WRITE legacy returned=$accepted " +
                            "op=${op.kind}"
                    )

                    if (accepted) {
                        condorGattWriteQueue.removeFirst()
                        condorGattWriteInFlight = op
                        condorTxWriteInFlight =
                            op.kind == CondorGattWriteKind.RX_FRAME

                        handler.removeCallbacks(
                            condorTxWriteTimeoutRunnable
                        )
                        handler.postDelayed(
                            condorTxWriteTimeoutRunnable,
                            condorTxWriteTimeoutMs
                        )
                    } else {
                        condorGattWriteQueue.removeFirst()
                        handleCondorGattWriteFailure(
                            gatt,
                            op,
                            -1
                        )
                    }
                }
            } catch (e: Exception) {
                log(
                    "CONDOR GATT WRITE EXCEPTION: " +
                        "${e.javaClass.simpleName}: ${e.message}; " +
                        "op=${op.description}"
                )
                condorGattWriteQueue.removeFirst()
                handleCondorGattWriteFailure(gatt, op, -1)
            }
        } finally {
            condorGattWritePumpRunning = false

            if (condorGattWritePumpRequested) {
                condorGattWritePumpRequested = false

                if (bluetoothGatt == gatt && isGattConnected) {
                    handler.post {
                        pumpCondorGattWriteQueue(gatt)
                    }
                }
            }
        }
    }
    private fun handleCondorGattWriteFailure(
        gatt: BluetoothGatt,
        op: CondorGattWriteOp,
        status: Int,
        statusName: String = gattStatusName(status)
    ) {
        log(
            "CONDOR GATT WRITE FAILED: ${op.description}; " +
                "status=$status ($statusName)"
        )

        if (op.kind == CondorGattWriteKind.RX_FRAME) {
            condorTxWriteInFlight = false
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
            condorUrgentTxFrameQueue.clear()
            log(
                "CONDOR TX: aborting current frame after GATT write failure"
            )
        } else {
            log(
                "CONDOR TX_ACK write failed; RX frame state left intact"
            )
        }

        if (bluetoothGatt == gatt && isGattConnected) {
            pumpCondorGattWriteQueue(gatt)
        }
    }

    private fun handleCondorGattWriteCallback(
        gatt: BluetoothGatt,
        characteristicUuid: UUID,
        status: Int
    ) {
        val op = condorGattWriteInFlight

        if (op == null) {
            log(
                "CONDOR GATT WRITE CALLBACK: unexpected callback; " +
                    "uuid=$characteristicUuid status=$status " +
                    "(${gattStatusName(status)})"
            )
            return
        }

        if (op.characteristicUuid != characteristicUuid) {
            log(
                "CONDOR GATT WRITE CALLBACK: UUID mismatch; " +
                    "expected=${op.characteristicUuid} " +
                    "actual=$characteristicUuid; status=$status"
            )
            return
        }

        handler.removeCallbacks(condorTxWriteTimeoutRunnable)
        condorGattWriteInFlight = null
        condorTxWriteInFlight = false

        if (status != BluetoothGatt.GATT_SUCCESS) {
            handleCondorGattWriteFailure(gatt, op, status)
            return
        }

        val callbackAgeMs =
            SystemClock.elapsedRealtime() -
                condorDiagLastSuccessfulGattWriteAtMs

        log(
            "CONDOR GATT WRITE CALLBACK OK: ${op.description}"
        )
        recordCondorGattHistory(
            "WRITE_CALLBACK_OK ${op.description} ageFromLastAccepted=${callbackAgeMs}ms"
        )

        if (op.kind == CondorGattWriteKind.RX_FRAME) {
            // Only now is this Condor fragment complete.
            condorNextTxSeq =
                (condorNextTxSeq + 1) and CondorProtocol.SEQ_MASK

            condorTxActiveOffset = op.frameEndOffset

            if (
                condorTxActiveFrame != null &&
                condorTxActiveOffset >= condorTxActiveFrame!!.size
            ) {
                log("CONDOR TX: complete frame sent successfully")
                condorTxActiveFrame = null
                condorTxActiveOffset = 0
            }
        }

        /*
         * A TX_ACK has priority over continuing an RX_FRAME.
         * If an ACK is queued, dispatch it immediately.
         * Otherwise continue the active RX frame with the 100 ms diagnostic
         * pacing. This keeps ACK latency low without reintroducing the
         * Android GATT write congestion we are testing.
         */
        if (
            condorGattWriteQueue.peekFirst()?.kind ==
                CondorGattWriteKind.TX_ACK
        ) {
            log(
                "CONDOR GATT WRITE CALLBACK: TX_ACK queued; " +
                    "dispatching ACK before RX_FRAME continuation"
            )
            pumpCondorGattWriteQueue(gatt)
        } else if (
            condorGattWriteQueue.isEmpty() &&
            condorTxActiveFrame != null &&
            !condorTxWriteInFlight
        ) {
            scheduleCondorRxFrameContinuation(gatt)
        } else if (
            condorGattWriteQueue.isEmpty() &&
            condorTxActiveFrame == null &&
            (
                condorUrgentTxFrameQueue.isNotEmpty() ||
                condorTxFrameQueue.isNotEmpty()
            ) &&
            !condorTxWriteInFlight
        ) {
            if (condorUrgentTxFrameQueue.isNotEmpty()) {
                scheduleCondorPriorityFrameStart(gatt)
            } else {
                scheduleCondorRxFrameStart(gatt)
            }
        }

    }

    private fun scheduleCondorRxFrameContinuation(gatt: BluetoothGatt) {
        if (
            condorGattWritePacingScheduled ||
            bluetoothGatt != gatt ||
            !isGattConnected
        ) {
            return
        }

        condorGattWritePacingScheduled = true
        handler.postDelayed(
            condorGattWritePacingRunnable,
            condorGattWritePacingDelayMs
        )
        log(
            "CONDOR GATT WRITE: RX_FRAME continuation scheduled in " +
                "${condorGattWritePacingDelayMs} ms"
        )
    }

    private fun scheduleCondorRxFrameStart(gatt: BluetoothGatt) {
        if (
            condorGattWritePacingScheduled ||
            bluetoothGatt != gatt ||
            !isGattConnected
        ) {
            return
        }

        condorGattWritePacingScheduled = true
        handler.postDelayed(
            condorGattWritePacingRunnable,
            condorGattWritePacingDelayMs
        )
        log(
            "CONDOR GATT WRITE: next RX_FRAME start scheduled in " +
                "${condorGattWritePacingDelayMs} ms"
        )
    }

    private fun scheduleCondorPriorityFrameStart(gatt: BluetoothGatt) {
        if (
            condorGattWritePacingScheduled ||
            bluetoothGatt != gatt ||
            !isGattConnected ||
            condorTxActiveFrame != null ||
            condorUrgentTxFrameQueue.isEmpty() ||
            condorGattWriteQueue.isNotEmpty()
        ) {
            return
        }

        condorGattWritePacingScheduled = true
        handler.postDelayed(
            condorGattWritePacingRunnable,
            condorGattWritePriorityFrameDispatchDelayMs
        )
        log(
            "CONDOR GATT WRITE: urgent response will start in " +
                "${condorGattWritePriorityFrameDispatchDelayMs} ms"
        )
    }

    private fun recordCondorGattHistory(message: String) {
        val timestamp = timeFormat.format(Date())
        condorGattHistory.addLast("[$timestamp] $message")
        while (condorGattHistory.size > condorGattHistoryMaxEntries) {
            condorGattHistory.removeFirst()
        }
    }

    private fun dumpCondorGattHistory() {
        log("=== CONDOR GATT OP HISTORY (last ${condorGattHistory.size}) ===")
        for (entry in condorGattHistory) {
            log(entry)
        }
    }

    private fun resetCondorGattDiagnostics() {
        condorDiagLastTxNotificationAtMs = 0L
        condorDiagLastTxNotificationHex = ""
        condorDiagLastChangeIndicationAtMs = 0L
        condorDiagLastChangeIndicationHex = ""
        condorDiagLastChangeIndicationAckAtMs = 0L
        condorDiagLastSuccessfulGattWriteAtMs = 0L
        condorDiagLastSuccessfulGattWriteDescription = ""
        condorDiagLastGattBusyAtMs = 0L
        condorDiagLastGattBusyDescription = ""
        condorDiagLastGattCallbackAtMs = 0L
        condorDiagLastGattCallbackStatus = -1
        condorDiagLastGattCallbackState = -1
        condorDiagGattBusyTotal = 0
        condorDiagLastGenericRespAtMs = 0L
        condorDiagLastGenericRespSummary = ""
        condorDiagLastBleNotificationAtMs = 0L
        condorDiagLastBleNotificationUuid = ""
        condorGattHistory.clear()
    }

    private fun resetCondorTxNotificationTracking() {
        if (
            condorTxNotificationCopiesDropped > 0 ||
            condorTxNotificationResends > 0
        ) {
            log(
                "CONDOR TX notification tracking reset: " +
                    "copiesDropped=$condorTxNotificationCopiesDropped, " +
                    "resends=$condorTxNotificationResends"
            )
        }

        condorLastTxNotificationValue = null
        condorLastTxNotificationAtMs = 0L
        condorTxNotificationCopiesDropped = 0
        condorTxNotificationResends = 0
    }

    private val condorGattWritePacingRunnable = Runnable {
        condorGattWritePacingScheduled = false

        val gatt = bluetoothGatt
        if (
            gatt != null &&
            isGattConnected
        ) {
            /*
             * API33+ WRITE_NO_RESPONSE can complete from the immediate
             * writeCharacteristic() result without producing a callback
             * that we can use as the continuation signal.
             *
             * Therefore the pacing runnable must continue the Condor TX
             * frame itself when the GATT queue is empty. The previous
             * version only called pumpCondorGattWriteQueue(), which is a
             * no-op for an empty queue. That left multi-fragment frames
             * stranded after fragment #1.
             *
             * If a transport ACK is already queued, pump it first. The
             * ACK has priority. Its own successful immediate completion
             * will schedule another pacing pass, which then continues the
             * active RX frame.             */
            if (condorGattWriteQueue.isNotEmpty()) {
                pumpCondorGattWriteQueue(gatt)
            } else if (
                condorTxActiveFrame != null &&
                !condorTxWriteInFlight
            ) {
                log(
                    "CONDOR TX PACING: continuing active frame; " +
                        "offset=$condorTxActiveOffset/" +
                        "${condorTxActiveFrame!!.size}; " +
                        "nextSeq=${condorNextTxSeq and CondorProtocol.SEQ_MASK}"
                )
                sendNextCondorTxFragment(gatt)
            } else if (
                condorTxActiveFrame == null &&
                (
                    condorUrgentTxFrameQueue.isNotEmpty() ||
                    condorTxFrameQueue.isNotEmpty()
                ) &&
                !condorTxWriteInFlight
            ) {
                log(
                    "CONDOR TX PACING: starting next queued frame; " +
                        "urgentQueue=${condorUrgentTxFrameQueue.size}; " +
                        "normalQueue=${condorTxFrameQueue.size}"
                )
                startNextCondorTxFrame(gatt)
            }
        }
    }

    private fun resetCondorTxWriteQueue() {
        handler.removeCallbacks(condorTxWriteTimeoutRunnable)
        condorGattWriteRetryScheduled = false
        condorGattWritePacingScheduled = false
        handler.removeCallbacks(condorGattWritePacingRunnable)
        condorGattWriteInFlight = null
        condorGattWritePumpRequested = false
        condorGattWriteQueue.clear()

        condorTxWriteInFlight = false
        condorTxActiveFrame = null
        condorTxActiveOffset = 0
        condorTxFrameQueue.clear()
        condorUrgentTxFrameQueue.clear()
        condorTxActiveFrameIsUrgent = false
        condorTxActiveFrameDescription = ""
        condorTxActiveFrameEnqueuedAtMs = 0L
    }

    @SuppressLint("MissingPermission")
    private fun handleCondorTxNotification(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {
        condorLastActivityAtMs = SystemClock.elapsedRealtime()

        if (value.isEmpty()) {
            log("CONDOR TX: empty notification")
            return
        }

        /*
         * Match the upstream Condor behavior:
         *
         * The Android BLE stack can deliver the same notification multiple
         * times even though the brush sent it only once. If the bytes on
         * e50b0003 are identical to the previous delivery within 100 ms,
         * this is a host-side duplicate. Do not ACK it a second time and,
         * most importantly, do not append it to the frame buffer again.
         *
         * If the identical packet arrives after 100 ms, treat it as a real
         * device resend (most likely a lost TX_ACK). ACK it again, but do
         * not reassemble the packet twice.
         */
        val nowMs = SystemClock.elapsedRealtime()
        val previous = condorLastTxNotificationValue
        val ageMs =
            if (previous != null && previous.contentEquals(value)) {
                nowMs - condorLastTxNotificationAtMs
            } else {
                -1L
            }

        condorLastTxNotificationValue = value.copyOf()
        condorLastTxNotificationAtMs = nowMs

        if (ageMs >= 0L && ageMs < condorTxNotificationCopyWindowMs) {
            condorTxNotificationCopiesDropped++

            log(
                "CONDOR TX: duplicate notification dropped; " +
                    "same bytes ${ageMs} ms after previous delivery; " +
                    "copiesDropped=$condorTxNotificationCopiesDropped"
            )

            return
        }

        val seq =
            value[0].toInt() and CondorProtocol.SEQ_MASK

        val payload =
            value.copyOfRange(
                1,
                value.size
            )

        log("")
        log("=== CONDOR TX DATA ===")
        log("Transport seq=$seq")
        log("Packet HEX=${bytesToHex(value)}")

        if (ageMs >= condorTxNotificationCopyWindowMs) {
            condorTxNotificationResends++

            log(
                "CONDOR TX: packet appears resent after ${ageMs} ms; " +
                    "resends=$condorTxNotificationResends"
            )

            // A real resend must be ACKed again, but its payload was already
            // accepted into the frame buffer on the first delivery.
            sendCondorTxAck(gatt, seq)
            return
        }

        // ACK every new TX transport packet on e50b0004.
        sendCondorTxAck(gatt, seq)

        if (payload.isNotEmpty()) {
            log("Payload HEX=${bytesToHex(payload)}")

            val ascii = bytesToAscii(payload)
            if (ascii.isNotBlank()) {
                log("Payload ASCII=$ascii")
            }
        }

        condorRxFrameBuffer =
            condorRxFrameBuffer + payload

        log(
            "Condor RX frame buffer=${condorRxFrameBuffer.size} bytes"
        )

        parseCondorFrames()
    }


    @SuppressLint("MissingPermission")
    private fun sendCondorTxAck(
        gatt: BluetoothGatt,
        seq: Int
    ) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR TX_ACK skipped: GATT disconnected")
            return
        }

        val service = gatt.getService(CONDOR_SERVICE_UUID)
        if (service == null) {
            log("CONDOR TX_ACK: service not found")
            return
        }

        if (service.getCharacteristic(CHAR_WRITE_CFG_UUID) == null) {
            log(
                "CONDOR TX_ACK characteristic " +
                    "e50b0004 NOT FOUND"
            )
            return
        }

        val ackByte =
            (seq and CondorProtocol.SEQ_MASK).toByte()

        val ack =
            byteArrayOf(ackByte)

        val op = CondorGattWriteOp(
            kind = CondorGattWriteKind.TX_ACK,
            characteristicUuid = CHAR_WRITE_CFG_UUID,
            value = ack,
            description =
                "TX_ACK seq=${seq and CondorProtocol.SEQ_MASK}"
        )

        // ACKs share the same Android GATT queue as RX frame writes.
        // Put them first because transport ACKs must be sent promptly.
        if (
            condorGattWriteQueue.any {
                it.kind == CondorGattWriteKind.TX_ACK
            }
        ) {
            // Keep multiple transport ACKs in arrival order.
            condorGattWriteQueue.addLast(op)
        } else {
            // No queued ACK yet: put this ACK ahead of any queued RX frame.
            condorGattWriteQueue.addFirst(op)
        }

        log(
            "TX_ACK PAYLOAD HEX: ${bytesToHex(ack)}; " +
                "seq=${seq and CondorProtocol.SEQ_MASK}"
        )
        log(
            "TX_ACK FRAME/WRITE HEX: ${bytesToHex(ack)} -> e50b0004; " +
                "GATT queue=${condorGattWriteQueue.size}"
        )

        pumpCondorGattWriteQueue(gatt)
    }

    private fun parseCondorFrames() {
        while (true) {
            if (condorRxFrameBuffer.size < 5) {
                return
            }

            if (
                (condorRxFrameBuffer[0].toInt() and 0xFF) != 0xFE ||
                (condorRxFrameBuffer[1].toInt() and 0xFF) != 0xFF
            ) {
                log(
                    "CONDOR FRAME: invalid header, " +
                        "discarding first byte " +
                        "HEX=${bytesToHex(condorRxFrameBuffer)}"
                )

                condorRxFrameBuffer =
                    condorRxFrameBuffer.copyOfRange(
                        1,
                        condorRxFrameBuffer.size
                    )

                continue
            }

            val msgType =
                condorRxFrameBuffer[2].toInt() and 0xFF

            val bodyLength =
                ((condorRxFrameBuffer[3].toInt() and 0xFF) shl 8) or
                    (condorRxFrameBuffer[4].toInt() and 0xFF)

            val completeLength =
                5 + bodyLength

            if (condorRxFrameBuffer.size < completeLength) {
                log(
                    "CONDOR FRAME: waiting for body " +
                        "(${condorRxFrameBuffer.size}/$completeLength bytes)"
                )
                return
            }

            val body =
                condorRxFrameBuffer.copyOfRange(
                    5,
                    completeLength
                )

            condorRxFrameBuffer =
                condorRxFrameBuffer.copyOfRange(
                    completeLength,
                    condorRxFrameBuffer.size
                )

            log("")
            log("=== CONDOR FRAME RECEIVED ===")
            log("Message type=$msgType (${condorMessageTypeName(msgType)})")
            log("Body length=$bodyLength")
            log("Body HEX=${bytesToHex(body)}")

            val ascii =
                bytesToAscii(body)

            if (ascii.isNotBlank()) {
                log("Body ASCII=$ascii")
            }

            if (
                msgType == CondorProtocol.MSG_INITIALIZE_RESP ||
                msgType == CondorProtocol.MSG_GENERIC_RESP
            ) {
                parseCondorResponseBody(body)
            }

            if (msgType == CondorProtocol.MSG_CHANGE_IND) {
                handleCondorChangeIndication(body)
            }

            if (
                condorStage == 8 &&
                msgType == CondorProtocol.MSG_INITIALIZE_RESP
            ) {
                log(
                    "CONDOR InitializeResp received - " +
                        "framed transport is working"
                )
                mqttGateway.sessionUp()

                updateStatus(
                    "Condor InitializeResp přijata"
                )

                handler.postDelayed({
                    if (bluetoothGatt != null) {
                        attemptCondorGetProducts(bluetoothGatt!!)
                    }
                }, 250)
            } else if (
                msgType == CondorProtocol.MSG_GENERIC_RESP
            ) {
                handleCondorGenericResponse(body)
                // A request of the app itself may have finished; queued Home
                // Assistant requests can go now.
                mqttGateway.pump()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCondorChangeIndication(body: ByteArray) {
        log("")
        condorDiagLastChangeIndicationAtMs = SystemClock.elapsedRealtime()
        condorDiagLastChangeIndicationHex = bytesToHex(body)
        log("=== CONDOR CHANGE INDICATION ===")
        log("Body HEX=${bytesToHex(body)}")
        val ascii = bytesToAscii(body)
        if (ascii.isNotBlank()) log("Body ASCII=$ascii")

        // The upstream implementation ACKs every ChangeIndication with
        // MSG_CHANGE_IND_RESP and the exact two-byte payload 00 00.
        val gatt = bluetoothGatt
        if (gatt != null && isGattConnected) {
            val ack = CondorProtocol.changeIndicationAck()
            log("CHANGE_IND_RESP PAYLOAD HEX: 00 00")
            log("CHANGE_IND_RESP FRAME HEX: ${bytesToHex(ack)}")
            log("CHANGE_IND_RESP frame length=${ack.size} bytes")
            condorDiagLastChangeIndicationAckAtMs = SystemClock.elapsedRealtime()
            sendCondorFrame(
                gatt,
                ack,
                urgent = true,
                description = "CHANGE_IND_RESP"
            )
            mqttGateway.changeIndication(body)
        } else {
            log("CONDOR ChangeIndication ACK skipped: GATT disconnected")
        }
    }

    private fun handleCondorGenericResponse(body: ByteArray) {
        if (body.isEmpty()) {
            log("CONDOR GenericResp body is empty")
            if (condorPendingRequest == CondorPendingRequest.GATEWAY_CMD) {
                condorPendingRequest = CondorPendingRequest.NONE
                mqttGateway.onResponse(CondorMqttGateway.STATUS_NO_STATUS, null)
            }
            return
        }

        val status = body[0].toInt() and 0xFF
        val jsonText =
            if (body.size > 1) {
                String(body.copyOfRange(1, body.size), Charsets.UTF_8)
                    .trimEnd('\u0000')
                    .trim()
            } else {
                ""
            }

        condorDiagLastGenericRespAtMs = SystemClock.elapsedRealtime()
        condorDiagLastGenericRespSummary = "status=$status; json=${jsonText.take(120)}"
        log("CONDOR GenericResp status=$status")
        log("CONDOR GenericResp JSON=$jsonText")

        if (condorPendingRequest == CondorPendingRequest.GATEWAY_CMD) {
            condorPendingRequest = CondorPendingRequest.NONE
            mqttGateway.onResponse(status, jsonText)
            return
        }

        // Publish every successful non-empty Condor JSON response to MQTT.
        // Do not use condorPendingRequest here: that state describes our
        // outgoing discovery request and can be non-NONE while live
        // subscription data is received.
        if (status == 0 && jsonText.isNotBlank()) {
            publishCondorTelemetryToMqtt(jsonText)
        }

        if (condorIdleKeepAlivePending) {
            condorIdleKeepAlivePending = false

            if (status == 0) {
                condorIdleKeepAliveLastResponseAtMs =
                    SystemClock.elapsedRealtime()

                log("CONDOR IDLE KEEPALIVE RESPONSE OK")
                log(
                    "CONDOR IDLE KEEPALIVE response received; " +
                        "next probe in ${condorIdleKeepAliveIntervalMs / 1000}s"
                )
                if (jsonText.isNotBlank()) {
                    try {
                        val json = JSONObject(jsonText)
                        val batteryPercent = json.optInt("BatteryPercent", -1)
                        if (batteryPercent >= 0) {
                            log("CONDOR IDLE KEEPALIVE BatteryPercent=$batteryPercent")
                        }
                    } catch (e: Exception) {
                        log("CONDOR IDLE KEEPALIVE JSON parse note: ${e.message}")
                    }
                }
            } else {
                log("CONDOR IDLE KEEPALIVE RESPONSE FAILED: status=$status")
            }

            if (bluetoothGatt != null && isGattConnected) {
                handler.postDelayed(
                    condorIdleKeepAliveRunnable,
                    condorIdleKeepAliveIntervalMs
                )
            }

            // Do not let the diagnostic request enter the discovery state
            // machine below.
            return
        }

        if (status != 0) {
            log("CONDOR request failed with status=$status")

            // A single unsupported/failed port must not abort the complete
            // discovery. Continue with the next known item.
            val gatt = bluetoothGatt
            when (condorPendingRequest) {
                CondorPendingRequest.GET_PROPS -> {
                    condorPendingRequest = CondorPendingRequest.NONE
                    if (gatt != null && isGattConnected) {
                        handler.postDelayed({
                            requestNextCondorProps(gatt)
                        }, 250)
                    }
                }

                CondorPendingRequest.GET_PORTS -> {
                    condorPendingRequest = CondorPendingRequest.NONE
                    if (gatt != null && isGattConnected) {
                        requestNextCondorProduct()
                    }
                }

                CondorPendingRequest.GET_PRODUCTS -> {
                    condorPendingRequest = CondorPendingRequest.NONE
                    condorDiscoveryActive = false
                    updateStatus("Condor discovery selhala")
                    // Nothing was read; report an empty snapshot so that
                    // requests from Home Assistant are not blocked.
                    mqttGateway.snapshotComplete()
                }

                CondorPendingRequest.GATEWAY_CMD -> {
                    // Handled above.
                }

                CondorPendingRequest.SUBSCRIBE -> {
                    log("CONDOR SUBSCRIBE declined: ${condorCurrentProductId}/${condorCurrentPort}")
                    condorSubscribeIndex++
                    if (gatt != null && isGattConnected) {
                        handler.postDelayed({ requestNextCondorSubscription(gatt) }, 150)
                    }
                }

                CondorPendingRequest.NONE -> {
                    condorPendingRequest = CondorPendingRequest.NONE
                }
            }
            return
        }

        when (condorPendingRequest) {
            CondorPendingRequest.GET_PRODUCTS -> {
                parseCondorProductsResponse(jsonText)
            }

            CondorPendingRequest.GET_PORTS -> {
                parseCondorPortsResponse(jsonText)
            }

            CondorPendingRequest.SUBSCRIBE -> {
                val productId = condorCurrentProductId ?: "<unknown>"
                val port = condorCurrentPort ?: "<unknown>"
                condorSubscribedPorts += productId to port
                log("CONDOR SUBSCRIBE OK: $productId / $port")
                condorPendingRequest = CondorPendingRequest.NONE
                condorSubscribeIndex++
                val gatt = bluetoothGatt
                if (gatt != null && isGattConnected) {
                    handler.postDelayed({ requestNextCondorSubscription(gatt) }, 150)
                }
            }

            CondorPendingRequest.GET_PROPS -> {
                val productId = condorCurrentProductId ?: "<unknown>"
                val port = condorCurrentPort ?: "<unknown>"

                log(
                    "CONDOR PROPS [$productId / $port]"
                )
                log("CONDOR PROPS JSON=$jsonText")

                // Keep a compact copy for the final discovery summary.
                condorDiscoveredProps +=
                    "$productId / $port -> $jsonText"
                mqttGateway.portRead(productId, port, jsonText)

                // Also print individual JSON properties. This makes it much
                // easier to spot battery/session/statistics fields in the log.
                try {
                    val props = JSONObject(jsonText)
                    val keys = props.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        log(
                            "  PROP $key = ${props.opt(key)}"
                        )
                    }
                } catch (e: Exception) {
                    log(
                        "CONDOR GetProps JSON is not an object: " +
                            "${e.message}"
                    )
                }

                condorPendingRequest = CondorPendingRequest.NONE

                val gatt = bluetoothGatt
                if (gatt != null && isGattConnected) {
                    handler.postDelayed({
                        requestNextCondorProps(gatt)
                    }, 250)
                }
            }

            CondorPendingRequest.GATEWAY_CMD -> {
                // Handled above.
            }

            CondorPendingRequest.NONE -> {
                log("CONDOR GenericResp received without pending request")
            }
        }
    }

    private fun publishCondorTelemetryToMqtt(jsonText: String) {
        val config = mqttManager.loadConfig()
        if (!config.enabled || !config.legacyDiscovery || jsonText.isBlank()) {
            return
        }

        try {
            val json = JSONObject(jsonText)

            val port = when {
                json.has("BatteryPercent") || json.has("BatteryMV") ->
                    "battery"

                json.has("SessionID") &&
                    json.has("Duration") &&
                    json.has("Length") ->
                    "routine"

                json.has("LifetimeUsage") ||
                    json.has("LifetimeLimit") ||
                    json.has("NfcTagVersion") ->
                    "brushhead"

                json.has("HandleState") ||
                    json.has("PhysModes") ||
                    json.has("RoutineIDs") ->
                    "sonicare"

                else -> "telemetry"
            }

            mqttManager.publishTelemetry(
                port = port,
                json = json.toString()
            )
        } catch (e: Exception) {
            log("MQTT: Condor JSON is not an object; telemetry skipped: ${e.message}")
        }
    }

    private fun parseCondorProductsResponse(jsonText: String) {
        try {
            val obj = JSONObject(jsonText)
            condorProductIds.clear()

            val keys = obj.keys()
            while (keys.hasNext()) {
                condorProductIds += keys.next()
            }

            // Numeric product IDs are expected here. Sorting keeps the
            // discovery order stable between runs.
            condorProductIds.sortWith(compareBy<String> {
                it.toIntOrNull() ?: Int.MAX_VALUE
            }.thenBy { it })

            log("CONDOR PRODUCTS COUNT=${condorProductIds.size}")
            for (productId in condorProductIds) {
                val product = obj.optJSONObject(productId)
                log(
                    "  PRODUCT $productId: " +
                        (product?.optString("name", "<no name>") ?: "<no name>")
                )
            }

            if (condorProductIds.isEmpty()) {
                log("CONDOR: no product IDs returned")
                condorPendingRequest = CondorPendingRequest.NONE
                condorDiscoveryActive = false
                mqttGateway.snapshotComplete()
                return
            }

            condorProductIndex = 0
            val gatt = bluetoothGatt
            if (gatt == null || !isGattConnected) {
                log("CONDOR: GATT disappeared before live subscriptions")
                condorPendingRequest = CondorPendingRequest.NONE
                condorDiscoveryActive = false
                return
            }

            // IMPORTANT: Subscribe first. The upstream implementation
            // establishes the long-lived Condor subscriptions immediately
            // after GetProducts, before walking through GetPorts/GetProps.
            // This is also the behavior we want to test against the
            // toothbrush's ~20 second idle sleep.
            handler.postDelayed({
                startCondorLiveSubscriptions(gatt)
            }, 250)
        } catch (e: Exception) {
            log("CONDOR GetProducts JSON parse failed: ${e.message}")
            condorPendingRequest = CondorPendingRequest.NONE
            condorDiscoveryActive = false
            mqttGateway.snapshotComplete()
        }
    }

    private fun parseCondorPortsResponse(jsonText: String) {
        try {
            val array = JSONArray(jsonText)
            condorPorts.clear()

            for (i in 0 until array.length()) {
                val value = array.opt(i)
                if (value is String && value.isNotBlank()) {
                    condorPorts += value
                }
            }

            condorPorts.sort()

            log(
                "CONDOR PORTS [$condorCurrentProductId] " +
                    "COUNT=${condorPorts.size}: " +
                    condorPorts.joinToString()
            )

            // Binary ports (*.b) are streams; they can only be subscribed.
            if (condorPorts.removeAll { it.endsWith(".b") }) {
                log("CONDOR PORTS: binary *.b ports are not read")
            }

            if (condorPorts.isEmpty()) {
                log("CONDOR: no ports returned for $condorCurrentProductId")
                requestNextCondorProduct()
                return
            }

            condorPortIndex = 0
            val gatt = bluetoothGatt
            val productId = condorCurrentProductId

            if (
                gatt == null ||
                !isGattConnected ||
                productId == null
            ) {
                log("CONDOR: GATT disappeared before GetProps")
                condorPendingRequest = CondorPendingRequest.NONE
                condorDiscoveryActive = false
                return
            }

            handler.postDelayed({
                attemptCondorGetProps(
                    gatt,
                    productId,
                    condorPorts[condorPortIndex]
                )
            }, 250)
        } catch (e: Exception) {
            log("CONDOR GetPorts JSON parse failed: ${e.message}")
            requestNextCondorProduct()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNextCondorProps(gatt: BluetoothGatt) {
        condorPortIndex++

        if (condorPortIndex < condorPorts.size) {
            val productId = condorCurrentProductId ?: return
            attemptCondorGetProps(
                gatt,
                productId,
                condorPorts[condorPortIndex]
            )
            return
        }

        requestNextCondorProduct()
    }

    @SuppressLint("MissingPermission")
    private fun requestNextCondorProduct() {
        condorProductIndex++

        if (condorProductIndex < condorProductIds.size) {
            val gatt = bluetoothGatt ?: return
            attemptCondorGetPorts(
                gatt,
                condorProductIds[condorProductIndex]
            )
            return
        }

        condorPendingRequest = CondorPendingRequest.NONE
        condorDiscoveryActive = false

        mqttGateway.snapshotComplete()

        log("")
        log("=== CONDOR PRODUCT/PORT DISCOVERY COMPLETE ===")
        log(
            "Discovered products=${condorProductIds.size}, " +
                "successful GetProps=${condorDiscoveredProps.size}"
        )

        if (condorDiscoveredProps.isNotEmpty()) {
            log("=== CONDOR DISCOVERY SUMMARY ===")
            for (entry in condorDiscoveredProps) {
                log(entry)
            }
        } else {
            log("CONDOR: no successful GetProps responses")
        }

        val gatt = bluetoothGatt
        if (gatt != null && isGattConnected) {
            // Live subscriptions are intentionally started earlier, directly
            // after GetProducts. Reaching this point now means discovery is
            // complete and the long-lived subscriptions are already active.
            log("CONDOR: live subscriptions were established before discovery")
            updateStatus("Condor discovery dokončena; live subscriptions aktivní")

            // v0.1.31 diagnostic run: deliberately do NOT start the
            // artificial 5-second GetProps keep-alive. The previous runs
            // proved that the keep-alive did not prevent the BLE ACL
            // disconnect, and it also introduced extra traffic that was not
            // present in the upstream Home Assistant implementation.
            log("")
            log("=== CONDOR IDLE KEEP-ALIVE DISABLED (v0.1.31) ===")
            log("No artificial GetProps 1 / Battery probes will be sent")
            log("Reason: compare a clean upstream-like idle connection")
        } else {
            log("CONDOR: GATT disappeared after discovery")
            updateStatus("Condor discovery dokončena")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCondorIdleKeepAliveDiagnostic() {
        // Disabled in v0.1.31. Kept as a diagnostic helper so the old code
        // remains easy to restore for a controlled comparison if needed.
        log("CONDOR IDLE KEEP-ALIVE: DISABLED in v$APP_VERSION")
        handler.removeCallbacks(condorIdleKeepAliveRunnable)
        condorIdleKeepAlivePending = false
        return
        condorIdleKeepAliveCount = 0
        condorIdleKeepAliveLastResponseAtMs = 0L

        log("")
        log("=== CONDOR IDLE KEEP-ALIVE DIAGNOSTIC v2 / APP v$APP_VERSION ===")
        log("Interval: ${condorIdleKeepAliveIntervalMs / 1000} seconds")
        log("Method: normal GetProps 1 / Battery")
        log("Purpose: determine whether 257 occurs after a quiet interval")
        log("IMPORTANT: this is intentionally more aggressive than v0.1.25")

        handler.postDelayed(
            condorIdleKeepAliveRunnable,
            condorIdleKeepAliveIntervalMs
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendCondorIdleKeepAlive(gatt: BluetoothGatt) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR IDLE KEEPALIVE: GATT is no longer connected")
            return
        }

        if (condorIdleKeepAlivePending) {
            log("CONDOR IDLE KEEPALIVE: previous request still pending")
            handler.postDelayed(
                condorIdleKeepAliveRunnable,
                condorIdleKeepAliveIntervalMs
            )
            return
        }

        val frame = CondorProtocol.getPropsReq("1", "Battery")
        condorIdleKeepAlivePending = true
        condorIdleKeepAliveCount++

        val nowMs = SystemClock.elapsedRealtime()
        val sinceLastResponseMs =
            if (condorIdleKeepAliveLastResponseAtMs == 0L) {
                -1L
            } else {
                nowMs - condorIdleKeepAliveLastResponseAtMs
            }

        log("")
        log("=== CONDOR IDLE KEEPALIVE #$condorIdleKeepAliveCount ===")
        log("Sending GetProps 1 / Battery")
        log("reason: idle diagnostic test v2")
        log(
            "time since previous keepalive response=" +
                "${if (sinceLastResponseMs >= 0) "$sinceLastResponseMs ms" else "unknown"}"
        )
        log("Frame HEX=${bytesToHex(frame)}")

        sendCondorFrame(gatt, frame)

        handler.postDelayed({
            if (condorIdleKeepAlivePending) {
                condorIdleKeepAlivePending = false
                log("CONDOR IDLE KEEPALIVE TIMEOUT: no GenericResp within 5 seconds")

                // Keep the diagnostic running even if one probe is lost.
                // The important observation is whether the GATT link itself
                // survives the repeated 5-second probes.
                if (bluetoothGatt != null && isGattConnected) {
                    handler.postDelayed(
                        condorIdleKeepAliveRunnable,
                        condorIdleKeepAliveIntervalMs
                    )
                }
            }
        }, 5_000L)
    }

    @SuppressLint("MissingPermission")
    private fun startCondorLiveSubscriptions(gatt: BluetoothGatt) {
        condorSubscribeIndex = 0
        condorSubscribedPorts.clear()

        log("")
        log("=== CONDOR LIVE SUBSCRIPTIONS ===")
        log("Using ${CondorProtocol.DEFAULT_SUBSCRIBE_PORTS.size} default live port subscriptions")
        log("NOTE: subscription watchdog is passive; it sends NO BLE traffic")

        condorLastActivityAtMs = SystemClock.elapsedRealtime()

        if (!condorIdleWatchdogRunning) {
            condorIdleWatchdogRunning = true
            handler.postDelayed(
                condorIdleWatchdogRunnable,
                condorIdleWatchdogIntervalMs
            )
        }

        requestNextCondorSubscription(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun requestNextCondorSubscription(gatt: BluetoothGatt) {
        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR SUBSCRIBE: GATT disappeared")
            return
        }

        if (condorSubscribeIndex >= CondorProtocol.DEFAULT_SUBSCRIBE_PORTS.size) {
            condorPendingRequest = CondorPendingRequest.NONE
            log("=== CONDOR LIVE SUBSCRIPTIONS COMPLETE ===")
            log(
                "All ${CondorProtocol.DEFAULT_SUBSCRIBE_PORTS.size} " +
                    "logical subscriptions returned GenericResp status=0"
            )
            log("Subscribed ports=${condorSubscribedPorts.size}")
            for ((product, port) in condorSubscribedPorts) {
                log("  SUBSCRIBED $product / $port")
            }

            // Subscriptions are now active. Continue with the existing
            // product -> port -> properties discovery sequence.
            val nextGatt = bluetoothGatt
            if (nextGatt != null && isGattConnected && condorProductIds.isNotEmpty()) {
                condorDiscoveryActive = true
                condorProductIndex = 0
                handler.postDelayed({
                    attemptCondorGetPorts(
                        nextGatt,
                        condorProductIds[condorProductIndex]
                    )
                }, 250)
            } else {
                condorPendingRequest = CondorPendingRequest.NONE
                updateStatus("Condor live subscriptions aktivní")
            }
            return
        }

        val (productId, port) = CondorProtocol.DEFAULT_SUBSCRIBE_PORTS[condorSubscribeIndex]
        condorCurrentProductId = productId
        condorCurrentPort = port
        condorPendingRequest = CondorPendingRequest.SUBSCRIBE

        val frame = CondorProtocol.subscribeReq(productId, port)
        log("")
        log("=== CONDOR SUBSCRIBE REQUEST ===")
        log("Product ID: $productId")
        log("Port: $port")
        log("Timeout: ${CondorProtocol.SUBSCRIBE_TIMEOUT_SECS} seconds")
        // Byte-level comparison with the upstream Condor implementation:
        // product_id + NUL + port + NUL + JSON timeout.
        log("SUBSCRIBE PAYLOAD HEX=${bytesToHex(frame.copyOfRange(5, frame.size))}")
        log("SUBSCRIBE FRAME HEX=${bytesToHex(frame)}")
        sendCondorFrame(gatt, frame)
    }

    private fun condorMessageTypeName(type: Int): String =
        CondorProtocol.messageTypeName(type)

    private fun parseCondorResponseBody(
        body: ByteArray
    ) {
        if (body.isEmpty()) {
            log("CONDOR response body is empty")
            return
        }

        log(
            "CONDOR response first byte/status=" +
                "${body[0].toInt() and 0xFF}"
        )

        if (body.size > 1) {
            val jsonBytes =
                body.copyOfRange(
                    1,
                    body.size
                )

            val text =
                String(
                    jsonBytes,
                    Charsets.UTF_8
                ).trimEnd('\u0000')

            if (text.isNotBlank()) {
                log("CONDOR response payload=$text")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptCondorVersionWrite(
        gatt: BluetoothGatt
    ) {
        log("")
        log("=== CONDOR V4 VERSION REQUEST ===")
        log("Target: $CHAR_CLIENT_CFG_UUID")
        log("Value: 03 04")

        attemptCondorClientCfgWrite(
            gatt,
            byteArrayOf(0x03, 0x04),
            "03 04"
        )
    }

    private fun analyzeServerCfg(
        value: ByteArray
    ) {

        log(
            "SERVER_CFG length=${value.size}"
        )

        when (value.size) {

            1 -> {

                val version =
                    value[0].toInt() and 0xFF

                log(
                    "SERVER_CFG possible version=" +
                        version
                )
            }

            6 -> {

                log(
                    "SERVER_CFG possible channel " +
                        "configuration"
                )

                log(
                    "SERVER_CFG 6-byte HEX=" +
                        bytesToHex(value)
                )
            }

            else -> {

                log(
                    "SERVER_CFG unknown payload"
                )
            }
        }
    }

    private fun propertiesToString(
        properties: Int
    ): String {

        val list = mutableListOf<String>()
        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_READ != 0
        ) {
            list.add("READ")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE != 0
        ) {
            list.add("WRITE")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            list.add("WRITE_NR")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_NOTIFY != 0
        ) {
            list.add("NOTIFY")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_INDICATE != 0
        ) {
            list.add("INDICATE")
        }

        return if (list.isEmpty()) {
            "NONE"
        } else {
            list.joinToString(", ")
        }
    }

    private fun bluetoothStatusCodeName(status: Int): String =
        when (status) {
            BluetoothStatusCodes.SUCCESS ->
                "SUCCESS"
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED ->
                "ERROR_BLUETOOTH_NOT_ALLOWED"
            BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED ->
                "ERROR_BLUETOOTH_NOT_ENABLED"
            BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED ->
                "ERROR_DEVICE_NOT_BONDED"
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
                "ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION"
            BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND ->
                "ERROR_PROFILE_SERVICE_NOT_BOUND"
            BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED ->
                "ERROR_GATT_WRITE_NOT_ALLOWED"
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY ->
                "ERROR_GATT_WRITE_REQUEST_BUSY"
            BluetoothStatusCodes.ERROR_UNKNOWN ->
                "ERROR_UNKNOWN"
            else ->
                "UNKNOWN_BLUETOOTH_STATUS"
        }

    private fun gattStatusName(
        status: Int
    ): String {

        return when (status) {

            BluetoothGatt.GATT_SUCCESS ->
                "GATT_SUCCESS"

            1 ->
                "GATT_INVALID_HANDLE"

            2 ->
                "GATT_READ_NOT_PERMITTED"

            3 ->
                "GATT_WRITE_NOT_PERMITTED"

            4 ->
                "GATT_INVALID_PDU"

            5 ->
                "GATT_INSUFFICIENT_AUTHENTICATION"

            6 ->
                "GATT_REQUEST_NOT_SUPPORTED"

            7 ->
                "GATT_INVALID_OFFSET"

            8 ->
                "GATT_INSUFFICIENT_AUTHORIZATION"

            9 ->
                "GATT_PREPARE_QUEUE_FULL"

            10 ->
                "GATT_ATTRIBUTE_NOT_FOUND"

            11 ->
                "GATT_ATTRIBUTE_NOT_LONG"

            12 ->
                "GATT_INSUFFICIENT_ENCRYPTION"

            13 ->
                "GATT_UNSUPPORTED_GROUP_TYPE"

            14 ->
                "GATT_UNKNOWN_ERROR"

            15 ->
                "GATT_INSUFFICIENT_RESOURCES"

            257 ->
                "GATT_ERROR"

            133 ->
                "GATT_ERROR_133"

            8_000 ->
                "GATT_CONNECTION_CONGESTED"

            else ->
                "UNKNOWN_STATUS"
        }
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        return bytes.joinToString(" ") {
            "%02X".format(
                it.toInt() and 0xFF
            )
        }
    }

    private fun bytesToAscii(
        bytes: ByteArray
    ): String {

        return buildString {

            for (b in bytes) {

                val c =
                    b.toInt()
                        .and(0xFF)
                        .toChar()

                if (
                    c.code in 32..126
                ) {
                    append(c)
                } else {
                    append('.')
                }
            }
        }
    }

    private fun initLogFile() {

        try {
            val timestamp =
                SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    .format(Date())

            logFileDisplayName = "sonicare_${timestamp}.log"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, logFileDisplayName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/SonicareBleDiagnostic"
                    )
                }

                logFileUri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )

                val uri = logFileUri
                if (uri != null) {
                    val output = contentResolver.openOutputStream(uri, "wa")
                    if (output != null) {
                        logFileWriter = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
                        return
                    }
                }

                throw IllegalStateException("MediaStore log file could not be opened")
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val directory = File(downloads, "SonicareBleDiagnostic")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Cannot create log directory: $directory")
                }

                val file = File(directory, logFileDisplayName)
                logFileWriter = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
                )
            }

        } catch (e: Exception) {
            logFileDisplayName = ""
            logFileUri = null
            logFileWriter = null
            // The diagnostic UI log continues to work even if file creation fails.
            android.util.Log.e(TAG, "Cannot create diagnostic log file", e)
        }
    }

    private fun writeLogLineToFile(line: String) {
        logFileExecutor.execute {
            try {
                synchronized(this@MainActivity) {
                    logFileWriter?.apply {
                        write(line)
                        newLine()
                        flush()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot write diagnostic log", e)
            }
        }
    }

    private fun scheduleUiLogFlush() {
        if (uiLogFlushScheduled.compareAndSet(false, true)) {
            handler.postDelayed(
                uiLogFlushRunnable,
                UI_LOG_FLUSH_INTERVAL_MS
            )
        }
    }

    private fun log(
        message: String
    ) {
        val timestamp =
            synchronized(timeFormat) {
                timeFormat.format(Date())
            }

        val line =
            "[$timestamp] $message"

        // Persist every line independently of the throttled on-screen preview.
        writeLogLineToFile(line)

        pendingUiLogLines.offer(line)
        scheduleUiLogFlush()
    }

    private fun updateStatus(
        status: String
    ) {

        runOnUiThread {
            statusView.text =
                "Status: $status"
        }
    }

    private fun copyLogToClipboard() {

        val clipboard =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            ClipData.newPlainText(
                "Sonicare BLE log",
                logView.text.toString()
            )

        clipboard.setPrimaryClip(clip)

        updateStatus(
            "Log zkopírován do schránky"
        )
    }

    override fun onResume() {
        super.onResume()
        log(
            "ACTIVITY LIFECYCLE: onResume; " +
                "isFinishing=$isFinishing"
        )
    }

    override fun onPause() {
        super.onPause()
        log(
            "ACTIVITY LIFECYCLE: onPause; " +
                "isFinishing=$isFinishing; " +
                "isChangingConfigurations=$isChangingConfigurations"
        )
    }

    override fun onStop() {
        super.onStop()
        log(
            "ACTIVITY LIFECYCLE: onStop; " +
                "isFinishing=$isFinishing; " +
                "isChangingConfigurations=$isChangingConfigurations"
        )
    }

    override fun onDestroy() {
        log("ACTIVITY LIFECYCLE: onDestroy")
        handler.removeCallbacks(uiLogFlushRunnable)
        pendingUiLogLines.clear()
        uiLogFlushScheduled.set(false)

        condorIdleWatchdogRunning = false
        handler.removeCallbacks(condorIdleWatchdogRunnable)
        handler.removeCallbacks(condorIdleKeepAliveRunnable)
        condorIdleKeepAlivePending = false
        handler.removeCallbacks(autoScanRestartRunnable)

        stopScan()

        // The running BLE/protocol controller is currently bound to this
        // Activity instance. If Android recreates the Activity (for example
        // after rotation), stop this session rather than leaving callbacks
        // pointed at a destroyed controller. Screen locking only calls
        // onStop(), so it does not reach this block.
        log(
            "FOREGROUND SERVICE: stopping because MainActivity is being destroyed; " +
                "changingConfigurations=$isChangingConfigurations"
        )
        bleService?.shutdownGateway()
        stopService(SonicareBleForegroundService.startIntent(this))
        localBluetoothGatt = null
        gattConnectInProgress = false

        if (isBleServiceBound) {
            try {
                unbindService(bleServiceConnection)
            } catch (_: Exception) {
            }
            isBleServiceBound = false
            bleService = null
        }
        isBleServiceBinding = false

        // Service shutdown happens before the file writer is closed so its
        // final lifecycle/GATT messages are included in this session's log.
        logFileExecutor.execute {
            try {
                synchronized(this@MainActivity) {
                    logFileWriter?.flush()
                    logFileWriter?.close()
                    logFileWriter = null
                }
            } catch (_: Exception) {
            }
        }
        logFileExecutor.shutdown()
        try {
            logFileExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            mqttManager.disconnect()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
