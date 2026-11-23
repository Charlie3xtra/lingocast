package com.lingocast.app.playback

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class ShadowState(
    val isRecording: Boolean = false,
    val isPlayingBack: Boolean = false,
    val hasRecording: Boolean = false,
    val amplitudeNormalized: Float = 0f,
    val error: String? = null
)

class ShadowRecorder(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var meterJob: Job? = null

    private val recordingFile: File
        get() = File(context.cacheDir, "shadow_latest.m4a")

    private val _state = MutableStateFlow(ShadowState())
    val state: StateFlow<ShadowState> = _state.asStateFlow()

    fun startRecording() {
        stopPlayback()
        stopRecording()
        runCatching {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(recordingFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            _state.value = _state.value.copy(
                isRecording = true,
                hasRecording = false,
                error = null
            )
            meterJob = scope.launch {
                while (isActive && _state.value.isRecording) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val norm = (amp / 18000f).coerceIn(0.05f, 1f)
                    _state.value = _state.value.copy(amplitudeNormalized = norm)
                    delay(65)
                }
            }
        }.onFailure { err ->
            _state.value = _state.value.copy(
                isRecording = false,
                error = "无法启动录音：${err.message ?: "请检查麦克风权限"}"
            )
        }
    }

    fun stopRecording() {
        meterJob?.cancel()
        meterJob = null
        recorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        recorder = null
        _state.value = _state.value.copy(
            isRecording = false,
            amplitudeNormalized = 0f,
            hasRecording = recordingFile.isFile() && recordingFile.length() > 512L
        )
    }

    fun playRecording() {
        if (!recordingFile.isFile()) return
        stopPlayback()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(recordingFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPlayingBack = false)
                }
                start()
            }
            mediaPlayer = mp
            _state.value = _state.value.copy(isPlayingBack = true, error = null)
        }.onFailure { err ->
            _state.value = _state.value.copy(isPlayingBack = false, error = err.message)
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        _state.value = _state.value.copy(isPlayingBack = false)
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}
