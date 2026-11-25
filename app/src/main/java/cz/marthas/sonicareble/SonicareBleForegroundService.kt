package cz.marthas.sonicareble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the application process in foreground-service state while the
 * Sonicare BLE gateway is active.
 *
 * Phase 1 deliberately leaves the existing GATT implementation in
 * MainActivity. This service is a process-lifetime guard; the next step can
 * move GATT ownership into this service once lock-screen behavior is verified.
 */
class SonicareBleForegroundService : Service() {

    companion object {
        private const val TAG = "SonicareBleService"
        private const val CHANNEL_ID = "sonicare_ble_gateway"
        private const val NOTIFICATION_ID = 7401

        fun startIntent(context: android.content.Context): Intent =
            Intent(context, SonicareBleForegroundService::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Foreground service created")
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

            Log.i(TAG, "Foreground service active; type=connectedDevice")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enter connectedDevice foreground state", e)
            stopSelf(startId)
        }

        // The service is started again by MainActivity after the user opens it.
        // It does not claim to own or restore a GATT connection in this phase.
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sonicare BLE gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Stav služby pro udržení BLE gateway na pozadí"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
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
            .setContentText("Gateway běží na pozadí")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }
}
