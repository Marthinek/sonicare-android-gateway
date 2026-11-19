package cz.marthas.sonicareble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SonicareBLE"

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

        // Condor framed protocol message types
        private const val CONDOR_MSG_INITIALIZE_REQ = 1
        private const val CONDOR_MSG_INITIALIZE_RESP = 2
        private const val CONDOR_MSG_PUT_PROPS = 3
        private const val CONDOR_MSG_GET_PROPS = 4
        private const val CONDOR_MSG_SUBSCRIBE = 5
        private const val CONDOR_MSG_UNSUBSCRIBE = 6
        private const val CONDOR_MSG_GENERIC_RESP = 7
        private const val CONDOR_MSG_GET_PRODUCTS = 10
        private const val CONDOR_MSG_GET_PORTS = 11

        // 1-byte transport header: low 6 bits are sequence number.
        private const val CONDOR_SEQ_MASK = 0x3F

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

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var logView: TextView
    private lateinit var statusView: TextView

    private var selectedDevice: BluetoothDevice? = null
    private var isScanning = false

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
    private val condorTxWriteTimeoutMs = 5_000L
    private val condorTxWriteTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt
        if (condorTxWriteInFlight && gatt != null) {
            log(
                "CONDOR TX WRITE TIMEOUT after ${condorTxWriteTimeoutMs} ms; " +
                    "BLE write callback did not arrive"
            )
            log(
                "CONDOR TX: aborting current frame to avoid sending " +
                    "fragments out of order"
            )
            condorTxWriteInFlight = false
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
        }
    }

    // Protocol discovery state. The actual HX741X product/port names are
    // discovered from the toothbrush instead of being hard-coded.
    private enum class CondorPendingRequest {
        NONE,
        GET_PRODUCTS,
        GET_PORTS,
        GET_PROPS
    }

    private var condorPendingRequest = CondorPendingRequest.NONE
    private val condorProductIds = mutableListOf<String>()
    private val condorPorts = mutableListOf<String>()
    private var condorProductIndex = 0
    private var condorPortIndex = 0
    private var condorCurrentProductId: String? = null
    private var condorCurrentPort: String? = null
    private var condorDiscoveryActive = false

    // Human-readable record of every successful GetProps response.
    private val condorDiscoveredProps = mutableListOf<String>()

    private val scanDurationMs = 30_000L

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

        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE)
                    as BluetoothManager

        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter.bluetoothLeScanner

        requestBlePermissionsIfNeeded()

        log("=== SONICARE BLE DIAGNOSTIC v0.1.14 ===")
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

        val scanButton = Button(this)
        scanButton.text = "Scan"

        scanButton.setOnClickListener {
            startScan()
        }

        root.addView(scanButton)

        val connectButton = Button(this)
        connectButton.text = "Connect"

        connectButton.setOnClickListener {

            if (bluetoothGatt != null) {
                disconnectGatt()
            } else {
                connectSelectedDevice()
            }
        }

        root.addView(connectButton)

        val copyButton = Button(this)
        copyButton.text = "Kopírovat log"

        copyButton.setOnClickListener {
            copyLogToClipboard()
        }

        root.addView(copyButton)

        val clearButton = Button(this)
        clearButton.text = "Vymazat log"

        clearButton.setOnClickListener {
            logView.text = ""
        }

        root.addView(clearButton)

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

        updateStatus("Skenuji BLE...")

        isScanning = true

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        log("SCAN settings: LOW_LATENCY, reportDelay=0ms, duration=${scanDurationMs}ms")

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
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            log("SCAN stop exception: ${e.message}")
        }

        isScanning = false

        log("=== BLE SCAN STOP ===")
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {

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

        // Make sure BLE scanning is stopped before opening GATT.
        stopScan()

        gattConnectInProgress = true
        gattConnectionGeneration++
        val generation = gattConnectionGeneration

        log("")
        log("=== GATT CONNECT ===")
        log("Address: ${device.address}")
        log("Connection generation=$generation")

        try {
            val newGatt =
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

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

                    isGattConnected = false
                    gattConnectInProgress = false
                    condorDiscoveryActive = false
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
                    characteristic.uuid ==
                    CHAR_WRITE_NR_UUID &&
                    condorTxWriteInFlight
                ) {
                    handleCondorFrameWriteCallback(
                        gatt,
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
                    "writeDescriptor API33+ immediateResult=$result"
                )

                if (
                    result != BluetoothGatt.GATT_SUCCESS
                ) {
                    log(
                        "ERROR: CCCD write was not accepted by Android " +
                            "(immediate status=$result)"
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
                    "writeDescriptor API33+ $label: result=$result, " +
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
                        "returned=$result"
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
                        "returned=$result"
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

        val frame =
            byteArrayOf(
                0xFE.toByte(),
                0xFF.toByte(),
                CONDOR_MSG_INITIALIZE_REQ.toByte(),
                0x00,
                0x00
            )

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

        val frame =
            byteArrayOf(
                0xFE.toByte(),
                0xFF.toByte(),
                CONDOR_MSG_GET_PRODUCTS.toByte(),
                0x00,
                0x00
            )

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
        val frame = buildCondorFrame(CONDOR_MSG_GET_PORTS, body)

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

        val frame = buildCondorFrame(CONDOR_MSG_GET_PROPS, body)

        log("")
        log("=== CONDOR GET PROPS REQUEST ===")
        log("Message type: 4 (GetProps)")
        log("Product ID: $productId")
        log("Port: $port")
        log("Body length: ${body.size}")
        log("Body HEX: ${bytesToHex(body)}")
        log("Frame HEX: ${bytesToHex(frame)}")
        log("Transport sequence start: $condorNextTxSeq")

        sendCondorFrame(gatt, frame)
        updateStatus("Condor GetProps: $productId / $port")
    }

    private fun buildCondorFrame(
        messageType: Int,
        body: ByteArray
    ): ByteArray {
        val frame = ByteArray(5 + body.size)
        frame[0] = 0xFE.toByte()
        frame[1] = 0xFF.toByte()
        frame[2] = messageType.toByte()
        frame[3] = ((body.size shr 8) and 0xFF).toByte()
        frame[4] = (body.size and 0xFF).toByte()
        body.copyInto(frame, 5)
        return frame
    }

    @SuppressLint("MissingPermission")
    private fun sendCondorFrame(
        gatt: BluetoothGatt,
        frame: ByteArray
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

        val characteristic =
            service.getCharacteristic(CHAR_WRITE_NR_UUID)

        if (characteristic == null) {
            log("CONDOR RX WRITE characteristic NOT FOUND")
            return
        }

        log(
            "CONDOR TX frame queued: ${frame.size} bytes; " +
                "BLE fragments=${((frame.size + 18) / 19)}; " +
                "writeInFlight=$condorTxWriteInFlight"
        )

        // Copy the frame so callers can safely reuse their temporary ByteArray.
        condorTxFrameQueue.add(frame.copyOf())

        if (!condorTxWriteInFlight && condorTxActiveFrame == null) {
            startNextCondorTxFrame(gatt)
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

        if (condorTxWriteInFlight) {
            return
        }

        if (condorTxActiveFrame == null) {
            if (condorTxFrameQueue.isEmpty()) {
                return
            }

            condorTxActiveFrame = condorTxFrameQueue.removeFirst()
            condorTxActiveOffset = 0

            log(
                "CONDOR TX: starting queued frame " +
                    "(${condorTxActiveFrame!!.size} bytes)"
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
            log("CONDOR TX: fragment send skipped, another write is in flight")
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

        // Channel config reported 244 bytes, but without an explicit ATT
        // MTU exchange Android normally gives us a 20-byte BLE payload.
        // One byte is reserved for the Condor transport sequence header.
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

        val seq =
            condorNextTxSeq and CONDOR_SEQ_MASK

        val packet =
            byteArrayOf(seq.toByte()) + chunk

        log(
            "CONDOR RX TX packet: " +
                "seq=$seq " +
                "offset=$condorTxActiveOffset..${end - 1} " +
                "${end}/${frame.size} bytes " +
                "HEX=${bytesToHex(packet)}"
        )

        try {
            val accepted: Boolean

            if (Build.VERSION.SDK_INT >= 33) {
                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        packet,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    )

                log(
                    "writeCharacteristic API33+ returned=$result"
                )

                accepted =
                    result == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet

                characteristic.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                accepted =
                    gatt.writeCharacteristic(characteristic)

                log(
                    "writeCharacteristic returned=$accepted"
                )
            }

            if (!accepted) {
                log(
                    "CONDOR TX fragment rejected immediately"
                )
                log(
                    "CONDOR TX: aborting current frame; " +
                        "no subsequent fragment will be sent"
                )
                condorTxWriteInFlight = false
                condorTxActiveFrame = null
                condorTxActiveOffset = 0
                condorTxFrameQueue.clear()
                return
            }

            // Only now, after Android accepted the write operation, advance
            // the Condor sequence and remember that this exact fragment is
            // waiting for onCharacteristicWrite().
            condorNextTxSeq =
                (condorNextTxSeq + 1) and CONDOR_SEQ_MASK

            condorTxActiveOffset = end
            condorTxWriteInFlight = true

            handler.removeCallbacks(condorTxWriteTimeoutRunnable)
            handler.postDelayed(
                condorTxWriteTimeoutRunnable,
                condorTxWriteTimeoutMs
            )

            log(
                "CONDOR TX: fragment accepted; waiting for " +
                    "onCharacteristicWrite() before sending next fragment"
            )
        } catch (e: Exception) {
            log(
                "CONDOR FRAME WRITE EXCEPTION: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            condorTxWriteInFlight = false
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
        }
    }

    private fun handleCondorFrameWriteCallback(
        gatt: BluetoothGatt,
        status: Int
    ) {
        handler.removeCallbacks(condorTxWriteTimeoutRunnable)

        if (!condorTxWriteInFlight) {
            log("CONDOR TX: unexpected RX WRITE callback")
            return
        }

        condorTxWriteInFlight = false

        if (status != BluetoothGatt.GATT_SUCCESS) {
            log(
                "CONDOR TX: BLE write callback FAILED: " +
                    "status=$status (${gattStatusName(status)})"
            )
            log(
                "CONDOR TX: aborting current frame; " +
                    "queued frames=${condorTxFrameQueue.size}"
            )
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
            return
        }

        log(
            "CONDOR TX: BLE write callback OK; " +
                "fragment complete at offset=$condorTxActiveOffset"
        )

        if (bluetoothGatt != gatt || !isGattConnected) {
            log("CONDOR TX: callback received after GATT disconnect")
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            condorTxFrameQueue.clear()
            return
        }

        val frame = condorTxActiveFrame

        if (frame != null && condorTxActiveOffset < frame.size) {
            // IMPORTANT: do not wait for Condor RX_ACK here. The upstream
            // implementation serializes the BLE write operation itself, then
            // immediately sends the next fragment.
            sendNextCondorTxFragment(gatt)
        } else {
            log("CONDOR TX: complete frame sent successfully")
            condorTxActiveFrame = null
            condorTxActiveOffset = 0
            startNextCondorTxFrame(gatt)
        }
    }

    private fun resetCondorTxWriteQueue() {
        handler.removeCallbacks(condorTxWriteTimeoutRunnable)
        condorTxWriteInFlight = false
        condorTxActiveFrame = null
        condorTxActiveOffset = 0
        condorTxFrameQueue.clear()
    }

    @SuppressLint("MissingPermission")
    private fun handleCondorTxNotification(
        gatt: BluetoothGatt,
        value: ByteArray
    ) {
        if (value.isEmpty()) {
            log("CONDOR TX: empty notification")
            return
        }

        val seq =
            value[0].toInt() and CONDOR_SEQ_MASK

        val payload =
            value.copyOfRange(
                1,
                value.size
            )

        log("")
        log("=== CONDOR TX DATA ===")
        log("Transport seq=$seq")
        log("Packet HEX=${bytesToHex(value)}")

        // ACK every received TX transport packet on e50b0004.
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
        val service =
            gatt.getService(CONDOR_SERVICE_UUID)

        if (service == null) {
            log("CONDOR TX_ACK: service not found")
            return
        }

        val characteristic =
            service.getCharacteristic(
                CHAR_WRITE_CFG_UUID
            )

        if (characteristic == null) {
            log(
                "CONDOR TX_ACK characteristic " +
                    "e50b0004 NOT FOUND"
            )
            return
        }

        val ackByte: Byte =
            (seq.toInt() and CONDOR_SEQ_MASK).toByte()

        val ack =
            byteArrayOf(
                ackByte
            )

        log(
            "CONDOR TX_ACK -> e50b0004 " +
                "HEX=${bytesToHex(ack)}"
        )

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result =
                    gatt.writeCharacteristic(
                        characteristic,
                        ack,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    )

                log(
                    "TX_ACK write API33+ returned=$result"
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = ack

                characteristic.writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                val result =
                    gatt.writeCharacteristic(
                        characteristic
                    )

                log(
                    "TX_ACK write returned=$result"
                )
            }
        } catch (e: Exception) {
            log(
                "TX_ACK WRITE EXCEPTION: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message}"
            )
        }
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
                msgType == CONDOR_MSG_INITIALIZE_RESP ||
                msgType == CONDOR_MSG_GENERIC_RESP
            ) {
                parseCondorResponseBody(body)
            }

            if (
                condorStage == 8 &&
                msgType == CONDOR_MSG_INITIALIZE_RESP
            ) {
                log(
                    "CONDOR InitializeResp received - " +
                        "framed transport is working"
                )

                updateStatus(
                    "Condor InitializeResp přijata"
                )

                handler.postDelayed({
                    if (bluetoothGatt != null) {
                        attemptCondorGetProducts(bluetoothGatt!!)
                    }
                }, 250)
            } else if (
                msgType == CONDOR_MSG_GENERIC_RESP
            ) {
                handleCondorGenericResponse(body)
            }
        }
    }

    private fun handleCondorGenericResponse(body: ByteArray) {
        if (body.isEmpty()) {
            log("CONDOR GenericResp body is empty")
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

        log("CONDOR GenericResp status=$status")
        log("CONDOR GenericResp JSON=$jsonText")

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

            CondorPendingRequest.NONE -> {
                log("CONDOR GenericResp received without pending request")
            }
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
                return
            }

            condorProductIndex = 0
            val gatt = bluetoothGatt
            if (gatt == null || !isGattConnected) {
                log("CONDOR: GATT disappeared before GetPorts")
                condorPendingRequest = CondorPendingRequest.NONE
                condorDiscoveryActive = false
                return
            }

            handler.postDelayed({
                attemptCondorGetPorts(
                    gatt,
                    condorProductIds[condorProductIndex]
                )
            }, 250)
        } catch (e: Exception) {
            log("CONDOR GetProducts JSON parse failed: ${e.message}")
            condorPendingRequest = CondorPendingRequest.NONE
            condorDiscoveryActive = false
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

        updateStatus("Condor discovery dokončena")
    }

    private fun condorMessageTypeName(
        type: Int
    ): String {
        return when (type) {
            1 -> "InitializeReq"
            2 -> "InitializeResp"
            3 -> "PutProps"
            4 -> "GetProps"
            5 -> "Subscribe"
            6 -> "Unsubscribe"
            7 -> "GenericResp"
            8 -> "ChangeIndication"
            9 -> "ChangeIndicationResponse"
            10 -> "GetProducts"
            11 -> "GetPorts"
            else -> "Unknown"
        }
    }

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

    private fun log(
        message: String
    ) {

        val timestamp =
            timeFormat.format(Date())

        val line =
            "[$timestamp] $message"

        runOnUiThread {

            logView.append(
                "$line\n"
            )

            logView.parent
                ?.let {
                    if (it is ScrollView) {
                        it.post {
                            it.fullScroll(
                                ScrollView.FOCUS_DOWN
                            )
                        }
                    }
                }
        }
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

    override fun onDestroy() {

        stopScan()

        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        bluetoothGatt = null
        gattConnectInProgress = false

        super.onDestroy()
    }
}
