package com.example.ui.chat

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.CallSession
import com.example.service.CallForegroundService
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.TelegramBlue
import com.example.util.AudioCallEngine
import com.example.util.PermissionHelper
import kotlinx.coroutines.delay

@Composable
fun CameraXView(
    isFrontCamera: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } catch (e: Exception) {
                    Log.e("CameraXView", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } catch (e: Exception) {
                    Log.e("CameraXView", "Camera re-binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = modifier
    )
}

@Composable
fun CallScreen(
    session: CallSession,
    currentUserId: String = "",
    onEndCall: () -> Unit
) {
    val context = LocalContext.current
    var hasAudioPerm by remember { mutableStateOf(PermissionHelper.hasAudioPermission(context)) }
    var hasCameraPerm by remember { mutableStateOf(PermissionHelper.hasCameraPermission(context)) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasAudioPerm = PermissionHelper.hasAudioPermission(context)
        hasCameraPerm = PermissionHelper.hasCameraPermission(context)
    }

    LaunchedEffect(session.callType) {
        val needed = mutableListOf<String>()
        if (!PermissionHelper.hasAudioPermission(context)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (session.callType == "video" && !PermissionHelper.hasCameraPermission(context)) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            callPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    val audioEngine = remember { AudioCallEngine(context) }
    val micLevel by audioEngine.micLevel.collectAsState()

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerphoneOn by remember { mutableStateOf(session.callType == "video") }
    var isVideoEnabled by remember { mutableStateOf(session.callType == "video") }
    var isFrontCamera by remember { mutableStateOf(true) }
    var callSeconds by remember { mutableIntStateOf(0) }
    var ringingSeconds by remember { mutableIntStateOf(0) }

    val isConnected = session.status == "connected"
    val isCalling = session.status == "calling" || session.status == "ringing"
    val isRejected = session.status == "rejected"
    val isEnded = session.status == "ended" || session.status == "missed"

    // Identify the person at the other end of the call
    val otherPartyName = if (currentUserId.isNotBlank() && currentUserId == session.receiverId) {
        session.callerName.ifBlank { "Caller" }
    } else {
        session.receiverName.ifBlank { "User" }
    }

    // Start Android Foreground Service so ongoing call stays active in system notification shade when user exits app
    LaunchedEffect(session.callId, otherPartyName) {
        if (!isRejected && !isEnded) {
            CallForegroundService.startCallService(
                context = context,
                callId = session.callId,
                otherName = otherPartyName,
                callType = session.callType
            )
        }
    }

    // Start Audio Hardware Engine once call is connected
    LaunchedEffect(isConnected, isSpeakerphoneOn) {
        if (isConnected && hasAudioPerm) {
            audioEngine.start(speakerphone = isSpeakerphoneOn)
        }
    }

    // Handle mute state changes
    LaunchedEffect(isMuted) {
        audioEngine.setMute(isMuted)
    }

    // Handle speakerphone changes
    LaunchedEffect(isSpeakerphoneOn) {
        audioEngine.setSpeakerphone(isSpeakerphoneOn)
    }

    // Clean up audio hardware and foreground notification when call terminates or screen exits
    DisposableEffect(Unit) {
        onDispose {
            audioEngine.stop()
            CallForegroundService.stopCallService(context)
        }
    }

    // Only run timer when the call is actually CONNECTED (answered by other user)
    LaunchedEffect(session.status) {
        if (isConnected) {
            while (true) {
                delay(1000L)
                callSeconds++
            }
        }
    }

    // Ringing timeout: if ringing for > 45 seconds without being answered, auto-end call
    LaunchedEffect(session.status) {
        if (isCalling) {
            while (ringingSeconds < 45) {
                delay(1000L)
                ringingSeconds++
            }
            if (session.status == "calling") {
                onEndCall()
            }
        }
    }

    // Auto-dismiss after call is declined or ended
    LaunchedEffect(session.status) {
        if (isRejected || isEnded) {
            audioEngine.stop()
            CallForegroundService.stopCallService(context)
            delay(2500L)
            onEndCall()
        }
    }

    val minutes = callSeconds / 60
    val seconds = callSeconds % 60
    val formattedDuration = String.format("%02d:%02d", minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Real Live CameraX Video Stream
        if (session.callType == "video" && isVideoEnabled && hasCameraPerm) {
            CameraXView(
                isFrontCamera = isFrontCamera,
                modifier = Modifier.fillMaxSize()
            )

            // Subtle gradient overlay for readability of text and buttons
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )

            // Local PiP camera tile with camera indicator
            Box(
                modifier = Modifier
                    .padding(top = 48.dp, end = 20.dp)
                    .size(105.dp, 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, TelegramBlue, RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .align(Alignment.TopEnd)
            ) {
                CameraXView(
                    isFrontCamera = isFrontCamera,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = if (isFrontCamera) "Front Cam" else "Rear Cam",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Call Header & Info
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 56.dp, bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (session.callType == "video") "ChatConnect Video Call" else "ChatConnect Voice Call",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelegramBlue,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = otherPartyName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (!hasAudioPerm || (session.callType == "video" && !hasCameraPerm)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ErrorRed.copy(alpha = 0.25f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed),
                        modifier = Modifier.clickable {
                            val needed = mutableListOf<String>()
                            if (!hasAudioPerm) needed.add(Manifest.permission.RECORD_AUDIO)
                            if (session.callType == "video" && !hasCameraPerm) needed.add(Manifest.permission.CAMERA)
                            callPermissionLauncher.launch(needed.toTypedArray())
                        }
                    ) {
                        Text(
                            text = "⚠️ Tap to grant ${if (session.callType == "video" && !hasCameraPerm) "Camera & Mic" else "Microphone"}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Real status display based on actual Firestore call session status
                when {
                    isConnected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(OnlineGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formattedDuration,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                color = OnlineGreen
                            )
                        }
                    }
                    isRejected -> {
                        Text(
                            text = "Call Declined",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                    }
                    isEnded -> {
                        Text(
                            text = "Call Ended",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Ringing...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TelegramBlue
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Waiting for $otherPartyName to answer...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Audio Avatar with Voice-Reactive Pulse Animation
            if (session.callType == "audio" || !isVideoEnabled || !hasCameraPerm) {
                val voiceScale = if (isConnected) (1f + (micLevel * 0.4f)).coerceIn(1f, 1.4f) else 1f

                Box(
                    modifier = Modifier.size(170.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer wave ring
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .scale(if (isCalling) pulseScale else voiceScale)
                            .clip(CircleShape)
                            .background(
                                if (isRejected) ErrorRed.copy(alpha = 0.2f)
                                else if (isConnected && micLevel > 0.05f) OnlineGreen.copy(alpha = 0.25f)
                                else TelegramBlue.copy(alpha = 0.2f)
                            )
                    )
                    // Inner avatar circle
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRejected) ErrorRed
                                else if (isConnected) OnlineGreen
                                else TelegramBlue
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = otherPartyName.firstOrNull()?.uppercase() ?: "C",
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Call Controls Toolbar
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(32.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute Button (Controls AudioRecord hardware input)
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) ErrorRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { isMuted = !isMuted }) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute",
                                tint = if (isMuted) ErrorRed else Color.White
                            )
                        }
                    }

                    // Speakerphone Toggle
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakerphoneOn) TelegramBlue.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = { isSpeakerphoneOn = !isSpeakerphoneOn }) {
                            Icon(
                                imageVector = if (isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = "Speaker",
                                tint = if (isSpeakerphoneOn) TelegramBlue else Color.White
                            )
                        }
                    }

                    // Video Toggle if Video Call
                    if (session.callType == "video") {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(if (isVideoEnabled) Color.White.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { isVideoEnabled = !isVideoEnabled }) {
                                Icon(
                                    imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = "Toggle Video",
                                    tint = if (isVideoEnabled) Color.White else ErrorRed
                                )
                            }
                        }

                        // Flip Camera (Front / Rear)
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { isFrontCamera = !isFrontCamera }) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = "Switch Camera",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // End Call Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(ErrorRed),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                audioEngine.stop()
                                CallForegroundService.stopCallService(context)
                                onEndCall()
                            },
                            modifier = Modifier.testTag("end_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "End Call",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
