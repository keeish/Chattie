package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_CALLS_ID = "chatconnect_calls_channel"
    const val CHANNEL_MESSAGES_ID = "chatconnect_messages_channel"
    const val CHANNEL_INCOMING_CALL_ID = "chatconnect_incoming_call_channel"

    private const val CALL_NOTIFICATION_ID = 2001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Active ongoing call channel (Low to default importance, non-intrusive ongoing)
            val callsChannel = NotificationChannel(
                CHANNEL_CALLS_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications for ongoing voice and video calls"
                setShowBadge(false)
            }

            // Incoming messages channel (High importance with sound and vibration)
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .build()

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when friends or groups send messages"
                enableLights(true)
                enableVibration(true)
                setSound(defaultSoundUri, audioAttributes)
                setShowBadge(true)
            }

            // Incoming call ringing channel
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val incomingCallChannel = NotificationChannel(
                CHANNEL_INCOMING_CALL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming voice and video calls"
                enableLights(true)
                enableVibration(true)
                setSound(ringtoneUri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }

            notificationManager.createNotificationChannels(listOf(callsChannel, messagesChannel, incomingCallChannel))
        }
    }

    fun showIncomingMessageNotification(
        context: Context,
        conversationId: String,
        senderName: String,
        messageContent: String
    ) {
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_CONVERSATION_ID", conversationId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val textPreview = if (messageContent.isBlank()) "📷 Sent a photo" else messageContent

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(senderName)
            .setContentText(textPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(textPreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(conversationId.hashCode(), notification)
    }

    fun cancelMessageNotification(context: Context, conversationId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(conversationId.hashCode())
    }
}
