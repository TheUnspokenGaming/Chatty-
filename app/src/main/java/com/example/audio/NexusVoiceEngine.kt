package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

data class RecordedVoiceClip(
    val audioBytes: ByteArray,
    val durationMs: Long
)

/**
 * Real Android hardware voice recorder & encrypted audio player for Nexus.
 * Records AAC-LC M4A audio at 24 kbps (compact enough for fast zero-drop AES-256-GCM bursts),
 * tracks live microphone amplitude, and zero-overwrites temporary audio files immediately after use.
 */
class NexusVoiceEngine(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private var meterJob: Job? = null

    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var playbackJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun startRecording(): Result<Unit> {
        stopPlayback()
        cancelRecording()

        return runCatching {
            val dir = File(appContext.cacheDir, "nexus_voice_tmp").apply { mkdirs() }
            val outFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(24_000)
                setMaxDuration(60_000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            recordingFile = outFile
            recordingStartMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingDurationMs.value = 0L
            _micAmplitude.value = 0.08f

            meterJob?.cancel()
            meterJob = scope.launch {
                while (isActive && _isRecording.value) {
                    val elapsed = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)
                    _recordingDurationMs.value = elapsed
                    val rawAmp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    _micAmplitude.value = (rawAmp / 18_000f).coerceIn(0.05f, 1f)
                    delay(80L)
                }
            }
        }.onFailure {
            cancelRecording()
        }
    }

    fun stopRecording(): Result<RecordedVoiceClip> {
        meterJob?.cancel()
        meterJob = null

        val activeRecorder = recorder
        val file = recordingFile
        val elapsedMs = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)

        recorder = null
        recordingFile = null
        _isRecording.value = false
        _recordingDurationMs.value = 0L
        _micAmplitude.value = 0f

        if (activeRecorder == null || file == null) {
            return Result.failure(IllegalStateException("No active voice recording."))
        }

        return try {
            runCatching { activeRecorder.stop() }
            runCatching { activeRecorder.release() }

            if (!file.exists() || file.length() < 256L || elapsedMs < 300L) {
                shredTempFile(file)
                Result.failure(IllegalArgumentException("Hold or record for at least half a second."))
            } else {
                val bytes = file.readBytes()
                shredTempFile(file)
                Result.success(
                    RecordedVoiceClip(
                        audioBytes = bytes,
                        durationMs = min(elapsedMs, 60_000L)
                    )
                )
            }
        } catch (e: Exception) {
            shredTempFile(file)
            Result.failure(e)
        }
    }

    fun cancelRecording() {
        meterJob?.cancel()
        meterJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        recordingFile?.let { shredTempFile(it) }
        recordingFile = null
        _isRecording.value = false
        _recordingDurationMs.value = 0L
        _micAmplitude.value = 0f
    }

    fun togglePlayDecryptedAudio(messageId: String, audioBytes: ByteArray): Result<Unit> {
        if (_playingMessageId.value == messageId) {
            stopPlayback()
            return Result.success(Unit)
        }
        stopPlayback()

        return runCatching {
            val dir = File(appContext.cacheDir, "nexus_voice_tmp").apply { mkdirs() }
            val tempFile = File(dir, "play_${System.currentTimeMillis()}.m4a")
            tempFile.writeBytes(audioBytes)
            playbackFile = tempFile

            val mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    true
                }
                prepare()
                start()
            }

            player = mediaPlayer
            _playingMessageId.value = messageId
            _playbackProgress.value = 0f

            playbackJob?.cancel()
            playbackJob = scope.launch {
                while (isActive && _playingMessageId.value == messageId) {
                    val mp = player ?: break
                    val dur = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(1)
                    val pos = runCatching { mp.currentPosition }.getOrDefault(0).coerceAtLeast(0)
                    _playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    delay(90L)
                }
            }
        }.onFailure {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playbackFile?.let { shredTempFile(it) }
        playbackFile = null
        _playingMessageId.value = null
        _playbackProgress.value = 0f
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }

    private fun shredTempFile(file: File) {
        runCatching {
            if (file.exists()) {
                val len = file.length()
                if (len > 0L && len <= 4_000_000L) {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(0)
                        val zeros = ByteArray(4096)
                        var remaining = len
                        while (remaining > 0L) {
                            val chunk = min(zeros.size.toLong(), remaining).toInt()
                            raf.write(zeros, 0, chunk)
                            remaining -= chunk
                        }
                    }
                }
                file.delete()
            }
        }
    }
}
