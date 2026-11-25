package cz.marthas.sonicareble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Owns the Android BLE scanner and BluetoothGatt connection.
 *
 * MainActivity remains the current Condor protocol/UI controller. The service
 * creates the BLE objects with its own Service context and forwards Android
 * callbacks to the currently bound controller. This keeps the physical BLE
 * connection and scanner attached to a foreground service while the screen is
 * off, without rewriting the proven Condor framing/queue implementation.
 */
class SonicareBleForegroundService : Service() {

    companion object {
        private const val TAG = "SonicareBleService"
        private const val CHANNEL_ID = "sonicare_ble_gateway"
        private const val NOTIFICATION_ID = 7401

        fun startIntent(context: android.content.Context): Intent =
            Intent(context, SonicareBleForegroundService::class.java)
    }

    inner class LocalBinder : Binder() {
        fun getService(): SonicareBleForegroundService = this@SonicareBleForegroundService
    }

    private val binder = LocalBinder()
    private var foregroundActive = false
    private var foregroundStartError: String? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var activeScanCallback: ScanCallback? = null
    private var scanCallbackProxy: ScanCallback? = null
    private var gattCallbackDelegate: BluetoothGattCallback? = null
    private var logListener: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothLeScanner = manager?.adapter?.bluetoothLeScanner
        serviceLog("Service created; BLE scanner available=${bluetoothLeScanner != null}")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            val notification = buildNotification()
            val serviceType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                serviceType
            )

            foregroundActive = true
            foregroundStartError = null
            serviceLog("Foreground active; type=connectedDevice")
        } catch (e: Exception) {
            foregroundActive = false
            foregroundStartError = "${e.javaClass.simpleName}: ${e.message}"
            serviceLog("FOREGROUND START FAILED: $foregroundStartError")
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun isForegroundActive(): Boolean = foregroundActive

    fun getForegroundStartError(): String? = foregroundStartError

    fun setLogListener(listener: ((String) -> Unit)?) {
        logListener = listener
    }

    fun setGattCallbackDelegate(callback: BluetoothGattCallback?) {
        gattCallbackDelegate = callback
        serviceLog("GATT callback delegate ${if (callback == null) "detached" else "attached"}")
    }

    fun getBluetoothGatt(): BluetoothGatt? = bluetoothGatt

    fun setBluetoothGatt(gatt: BluetoothGatt?) {
        bluetoothGatt = gatt
        serviceLog(
            if (gatt == null) "GATT reference cleared"
            else "GATT reference stored id=${System.identityHashCode(gatt)}"
        )
    }

    @Suppress("MissingPermission")
    fun startBleScan(
        settings: ScanSettings,
        delegate: ScanCallback
    ): Boolean {
        val scanner = bluetoothLeScanner ?: run {
            serviceLog("SCAN failed: BluetoothLeScanner is unavailable")
            return false
        }

        stopBleScan()
        val proxy = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                delegate.onScanResult(callbackType, result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                delegate.onBatchScanResults(results)
            }

            override fun onScanFailed(errorCode: Int) {
                serviceLog("BLE scan callback failed: errorCode=$errorCode")
                delegate.onScanFailed(errorCode)
            }
        }

        return try {
            activeScanCallback = delegate
            scanCallbackProxy = proxy
            scanner.startScan(null, settings, proxy)
            serviceLog("BLE scan started by foreground service")
            true
        } catch (e: Exception) {
            activeScanCallback = null
            scanCallbackProxy = null
            serviceLog("BLE scan start failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    @Suppress("MissingPermission")
    fun stopBleScan(delegate: ScanCallback? = null) {
        val activeDelegate = activeScanCallback
        if (activeDelegate == null) return
        if (delegate != null && delegate !== activeDelegate) return

        try {
            val proxy = scanCallbackProxy
            if (proxy != null) {
                bluetoothLeScanner?.stopScan(proxy)
            }
            serviceLog("BLE scan stopped by foreground service")
        } catch (e: Exception) {
            serviceLog("BLE scan stop failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            activeScanCallback = null
            scanCallbackProxy = null
        }
    }

    @Suppress("MissingPermission")
    fun connectGatt(
        device: BluetoothDevice,
        callbackHandler: Handler
    ): BluetoothGatt? {
        if (bluetoothGatt != null) {
            serviceLog("GATT connect ignored: an existing GATT object is still stored")
            return bluetoothGatt
        }

        val proxy = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                serviceLog("GATT callback in Service: state=$newState status=$status")
                gattCallbackDelegate?.onConnectionStateChange(gatt, status, newState)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                gattCallbackDelegate?.onServicesDiscovered(gatt, status)
            }

            @Deprecated("Deprecated in Android 13; use value overload")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                gattCallbackDelegate?.onCharacteristicRead(gatt, characteristic, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                gattCallbackDelegate?.onCharacteristicRead(gatt, characteristic, value, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                gattCallbackDelegate?.onDescriptorWrite(gatt, descriptor, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                gattCallbackDelegate?.onCharacteristicWrite(gatt, characteristic, status)
            }

            @Deprecated("Deprecated in Android 13; use value overload")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                gattCallbackDelegate?.onCharacteristicChanged(gatt, characteristic)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                gattCallbackDelegate?.onCharacteristicChanged(gatt, characteristic, value)
            }
        }

        return try {
            val gatt = device.connectGatt(
                this,
                false,
                proxy,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                callbackHandler
            )
            bluetoothGatt = gatt
            serviceLog(
                "GATT connect created from Service context; " +
                    "device=${device.address}; gattId=${System.identityHashCode(gatt)}"
            )
            gatt
        } catch (e: Exception) {
            serviceLog("GATT connect exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @Suppress("MissingPermission")
    fun disconnectGatt() {
        val gatt = bluetoothGatt ?: return
        serviceLog("GATT disconnect requested by client")
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            serviceLog("GATT disconnect exception: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    fun shutdownGateway() {
        serviceLog("Gateway shutdown requested")
        stopBleScan()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }
        gattCallbackDelegate = null
        logListener = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundActive = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceLog("Task removed; stopping BLE gateway")
        shutdownGateway()
        super.onTaskRemoved(rootIntent)
    }

    private fun serviceLog(message: String) {
        Log.i(TAG, message)
        try {
            logListener?.invoke(message)
        } catch (e: Exception) {
            Log.w(TAG, "Log listener failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sonicare BLE gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Stav služby pro udržení BLE gateway na pozadí"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Sonicare BLE Gateway")
            .setContentText("BLE spojení běží na pozadí")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceLog("Service destroyed")
        stopBleScan()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gattCallbackDelegate = null
        logListener = null
        foregroundActive = false
        super.onDestroy()
    }
}
