package cz.marthas.sonicareble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var logView: TextView
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private val seen = linkedMapOf<String, BluetoothDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (_: SecurityException) { null }
            val data = result.scanRecord?.bytes?.joinToString("") { "%02X".format(it) } ?: ""
            val line = "${device.address}  RSSI=${result.rssi}  name=${name ?: "-"}  raw=${data.take(120)}"
            if (!seen.containsKey(device.address)) {
                seen[device.address] = device
                appendLog("FOUND  $line")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("SCAN FAILED: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBlePermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Sonicare BLE Diagnostic 0.1.0"
            textSize = 20f
        }
        root.addView(title)

        val info = TextView(this).apply {
            text = "Test target: Philips Sonicare HX7410/02"
            textSize = 14f
        }
        root.addView(info)

        val scan = Button(this).apply {
            text = "SCAN 15 s"
            setOnClickListener { startScan() }
        }
        root.addView(scan)

        val clear = Button(this).apply {
            text = "CLEAR LOG"
            setOnClickListener { logView.text = "" }
        }
        root.addView(clear)

        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(logView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestBlePermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            appendLog("BLE permissions OK")
        }
    }

    private fun startScan() {
        if (!hasBlePermission()) {
            appendLog("BLE permission missing")
            requestBlePermissions()
            return
        }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            appendLog("Bluetooth is disabled")
            return
        }

        seen.clear()
        scanner = adapter.bluetoothLeScanner
        appendLog("=== SCAN START ===")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        handler.postDelayed({
            try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
            appendLog("=== SCAN STOP ===")
            appendLog("Devices seen: ${seen.size}")
        }, 15_000)
    }

    private fun hasBlePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasBlePermission()) return
        appendLog("Connecting to ${device.address}")
        gatt?.close()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            appendLog("GATT state=${newState} status=$status")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                appendLog("CONNECTED -> discoverServices()")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            appendLog("SERVICES DISCOVERED status=$status count=${g.services.size}")
            g.services.forEach { service ->
                appendLog("SERVICE ${service.uuid}")
                service.characteristics.forEach { c ->
                    val p = c.properties
                    val props = buildString {
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append(" READ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append(" WRITE")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append(" WRITE_NR")
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append(" NOTIFY")
                        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append(" INDICATE")
                    }
                    appendLog("  CHAR ${c.uuid}$props")
                }
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            appendLog("READ ${characteristic.uuid} status=$status data=${hex(value)}")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            appendLog("NOTIFY ${characteristic.uuid} data=${hex(value)}")
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    private fun appendLog(s: String) {
        runOnUiThread {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            logView.append("[$ts] $s\n")
        }
    }

    override fun onDestroy() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        gatt?.close()
        super.onDestroy()
    }
}
