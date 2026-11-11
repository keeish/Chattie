package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.util.NotificationHelper

class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val NOTIFICATION_ID = 4001

        const val ACTION_START_CALL = "com.example.service.ACTION_START_CALL"
        const val ACTION_END_CALL = "com.example.service.ACTION_END_CALL"

        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_OTHER_NAME = "EXTRA_OTHER_NAME"
        const val EXTRA_CALL_TYPE = "EXTRA_CALL_TYPE"

        fun startCallService(
            context: Context,
            callId: String,
            otherName: String,
            callType: String
        ) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_OTHER_NAME, otherName)
                putExtra(EXTRA_CALL_TYPE, callType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCallService(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_END_CALL
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChatConnect:CallWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_END_CALL) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_CALL) {
            val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
            val otherName = intent.getStringExtra(EXTRA_OTHER_NAME) ?: "ChatConnect Call"
            val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"

            wakeLock?.acquire(2 * 60 * 60 * 1000L) // max 2 hours

            val notification = buildCallNotification(callId, otherName, callType)

            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (callType == "video") {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            } else {
                0
            }

            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                // Fallback for security exceptions on strict OEM ROMs
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        return START_STICKY
    }

    private fun buildCallNotification(
        callId: String,
        otherName: String,
        callType: String
    ): Notification {
        // Tapping notification returns to MainActivity
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_CALL_ID", callId)
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this,
            100,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // End Call Action
        val endCallIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endCallPendingIntent = PendingIntent.getService(
            this,
            101,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (callType == "video") "ChatConnect Video Call" else "ChatConnect Voice Call"
        val content = "Active call with $otherName"

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_CALLS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(returnPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "Return to Call", returnPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endCallPendingIntent)
            .build()
    }

    private fun cleanupAndStop() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanupAndStop()
        super.onDestroy()
    }
}
