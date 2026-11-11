package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioCallEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    private var isMuted = false
    private var isRunning = false

    companion object {
        private const val TAG = "AudioCallEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun start(speakerphone: Boolean = false) {
        if (isRunning) return
        isRunning = true

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(speakerphone)
            audioManager.isMicrophoneMute = false

            val minRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val recordBufferSize = maxOf(minRecordBufferSize, 4096)

            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val trackBufferSize = maxOf(minTrackBufferSize, 4096)

            if (PermissionHelper.hasAudioPermission(context)) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    recordBufferSize
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioRecord?.startRecording()
                audioTrack?.play()

                recordingJob = scope.launch {
                    val buffer = ShortArray(recordBufferSize / 2)
                    while (isActive && isRunning) {
                        val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readCount > 0) {
                            if (!isMuted) {
                                // Calculate RMS mic level for visual feedback
                                var sum = 0.0
                                for (i in 0 until readCount) {
                                    sum += buffer[i] * buffer[i]
                                }
                                val rms = sqrt(sum / readCount)
                                val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                                _micLevel.value = normalized

                                // Feed audio pipeline so user voice flows in hardware communication mode
                                audioTrack?.write(buffer, 0, readCount)
                            } else {
                                _micLevel.value = 0f
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio engine: ${e.message}", e)
        }
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
        try {
            audioManager.isMicrophoneMute = muted
            if (muted) {
                _micLevel.value = 0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set mic mute: ${e.message}")
        }
    }

    fun setSpeakerphone(speaker: Boolean) {
        try {
            audioManager.isSpeakerphoneOn = speaker
        } catch (e: Exception) {
            Log.w(TAG, "Could not toggle speakerphone: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping record: ${e.message}")
        }
        audioRecord = null

        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping track: ${e.message}")
        }
        audioTrack = null

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting AudioManager: ${e.message}")
        }

        _micLevel.value = 0f
    }
}
