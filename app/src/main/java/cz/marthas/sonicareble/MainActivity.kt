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
        private const val CONDOR_MSG_GENERIC_RESP = 7

        // 1-byte transport header: low 6 bits are sequence number.
        private const val CONDOR_SEQ_MASK = 0x3F

        private val DEVICE_INFORMATION_SERVICE_UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

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

    private val scanDurationMs = 10_000L

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

            val containsCondor =
                rawAdvertisement?.let {
                    bytesToAscii(it).contains("Condor", ignoreCase = true)
                } == true

            log(
                "SCAN: ${device.address} " +
                    "RSSI=$rssi " +
                    "name=${name ?: "<null>"}" +
                    if (containsCondor) " [CONDOR]" else ""
            )

            if (containsCondor ||
                name?.contains("Sonicare", ignoreCase = true) == true
            ) {
                if (selectedDevice == null) {
                    selectedDevice = device

                    log(
                        "SCAN: selected ${device.address} " +
                            "name=${name ?: "<null>"}"
                    )

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

        log("=== SONICARE BLE DIAGNOSTIC v0.1.6 ===")
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

        bleScanner?.startScan(scanCallback)

        handler.postDelayed({
            stopScan()

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

        if (bluetoothGatt != null) {
            log("CONNECT: GATT already exists")
            return
        }

        log("")
        log("=== GATT CONNECT ===")
        log("Address: ${device.address}")

        try {
            bluetoothGatt =
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

            updateStatus("Připojuji...")
        } catch (e: Exception) {
            log("CONNECT EXCEPTION: ${e.message}")
            bluetoothGatt = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {

        log("GATT: disconnect requested")

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
                    "GATT state=$newState status=$status " +
                        "(${gattStatusName(status)})"
                )

                if (
                    newState ==
                    BluetoothGatt.STATE_CONNECTED
                ) {

                    log("GATT CONNECTED")

                    updateStatus("Připojeno")

                    handler.postDelayed({

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

                    updateStatus("Odpojeno")

                    bluetoothGatt = null
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
                    "=== STANDARD GATT READS ==="
                )

                readStandardCharacteristics(gatt)

                handler.postDelayed({

                    enableServerCfgNotify(gatt)

                }, 1000)
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
                            attemptCondorVersionWrite(gatt)
                        }, 500)
                    }

                    CHAR_NOTIFY_TX_UUID -> {
                        log("TX notify ENABLED")
                        condorStage = 2

                        handler.postDelayed({
                            enableCondorNotify(
                                gatt,
                                CHAR_NOTIFY_RX_UUID,
                                "RX_ACK"
                            )
                        }, 250)
                    }

                    CHAR_NOTIFY_RX_UUID -> {
                        log("RX_ACK notify ENABLED")
                        condorStage = 3

                        handler.postDelayed({
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
                        }, 250)
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
                            "CONDOR V4 confirmed -> enabling TX notify"
                        )

                        handler.postDelayed({
                            enableCondorNotify(
                                gatt,
                                CHAR_NOTIFY_TX_UUID,
                                "TX"
                            )
                        }, 250)
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
                            attemptCondorOpenRx(gatt)
                        }, 250)
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
                        attemptCondorInitializeReq(gatt)
                    }, 250)
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
                    "writeDescriptor API33+ result=$result"
                )

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
                    "writeDescriptor API33+ " +
                        "$label result=$result"
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
    private fun sendCondorFrame(
        gatt: BluetoothGatt,
        frame: ByteArray
    ) {
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

        // Channel config reported 244 bytes, but without an explicit ATT
        // MTU exchange Android normally gives us a 20-byte BLE payload.
        val maxBleWrite = 20
        val chunkPayloadSize = maxBleWrite - 1

        var offset = 0

        while (offset < frame.size) {
            val end =
                minOf(
                    offset + chunkPayloadSize,
                    frame.size
                )

            val chunk =
                frame.copyOfRange(
                    offset,
                    end
                )

            val seq =
                condorNextTxSeq and CONDOR_SEQ_MASK

            condorNextTxSeq =
                (condorNextTxSeq + 1) and CONDOR_SEQ_MASK

            val packet =
                byteArrayOf(seq.toByte()) + chunk

            log(
                "CONDOR RX TX packet: " +
                    "seq=$seq " +
                    "HEX=${bytesToHex(packet)}"
            )

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result =
                        gatt.writeCharacteristic(
                            characteristic,
                            packet,
                            BluetoothGattCharacteristic
                                .WRITE_TYPE_NO_RESPONSE
                        )

                    log(
                        "writeCharacteristic API33+ " +
                            "returned=$result"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = packet

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
                    "CONDOR FRAME WRITE EXCEPTION: " +
                        "${e.javaClass.simpleName}: " +
                        "${e.message}"
                )
                return
            }

            offset = end
        }
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

        val ack =
            byteArrayOf(
                (seq and CONDOR_SEQ_MASK).toByte()
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
            }
        }
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

        super.onDestroy()
    }
}
