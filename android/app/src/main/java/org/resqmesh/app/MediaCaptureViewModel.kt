package org.resqmesh.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.resqmesh.app.core.Attachment
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class CapturePhase { IDLE, STARTING, REQUESTING_PERMISSION, WAITING_CAMERA, RECORDING, PROCESSING, READY, CANCELLED, ERROR }

data class MediaCaptureState(val kind: String? = null, val phase: CapturePhase = CapturePhase.IDLE,
    val seconds: Int = 0, val attachment: DraftAttachment? = null, val notice: String? = null) {
    val recording get() = phase == CapturePhase.RECORDING
    val processing get() = phase == CapturePhase.PROCESSING
}

/** Captures only after a visible user action. No service or background microphone is used. */
class MediaCaptureViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(MediaCaptureState())
    val state = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAt = 0L
    private var timer: Job? = null
    private var preparing: Job? = null
    private var captureFile: File? = saved.get<String>("capturePath")?.let(::File)
    private val directory get() = File(getApplication<Application>().filesDir, "media-drafts").apply { mkdirs() }
    val pendingKind: String? get() = saved["cameraKind"]
    val pendingFile: File? get() = saved.get<String>("cameraPath")?.let(::File)

    init {
        val path = saved.get<String>("readyPath")
        val kind = saved.get<String>("readyKind")
        if (path != null && kind != null) finishFile(File(path), kind)
        else if (pendingKind in setOf("image", "video") && pendingFile != null) {
            transition(MediaCaptureState(kind = pendingKind, phase = CapturePhase.WAITING_CAMERA))
        } else {
            val interruptedKind = saved.get<String>("captureKind") ?: pendingKind
            clearPendingCamera()
            if (interruptedKind in setOf("audio", "image", "video")) {
                transition(MediaCaptureState(kind = interruptedKind, phase = CapturePhase.CANCELLED,
                    notice = "Capture was interrupted. Nothing was sent. Try again when ready."))
            }
        }
    }

    /** Select before navigation so permission and external-camera waits never look like an empty chooser. */
    fun selectCapture(kind: String, needsPermission: Boolean = false): Boolean {
        require(kind in setOf("audio", "image", "video"))
        require(!needsPermission || kind == "audio")
        if (state.value.attachment != null || state.value.phase in setOf(CapturePhase.STARTING,
                CapturePhase.REQUESTING_PERMISSION, CapturePhase.WAITING_CAMERA, CapturePhase.RECORDING,
                CapturePhase.PROCESSING, CapturePhase.READY)) return false
        discard()
        transition(MediaCaptureState(kind = kind,
            phase = if (needsPermission) CapturePhase.REQUESTING_PERMISSION else CapturePhase.STARTING))
        return true
    }

    /** Leaving a pending permission flow cancels its intent; a later callback cannot start recording. */
    fun cancelPermissionRequest() {
        if (state.value.phase != CapturePhase.REQUESTING_PERMISSION || state.value.kind != "audio") return
        transition(MediaCaptureState(kind = "audio", phase = CapturePhase.CANCELLED,
            notice = "Voice recording was not started. Nothing was sent. Try again when ready."))
    }

    fun fail(message: String) {
        preparing?.cancel(); preparing = null
        stopAndReleaseRecorder()
        clearPendingCamera()
        transition(state.value.copy(phase = if (state.value.attachment == null) CapturePhase.ERROR else CapturePhase.READY,
            notice = message))
    }

    fun startVoice() {
        if (state.value.kind != "audio" || state.value.attachment != null ||
            state.value.phase !in setOf(CapturePhase.STARTING, CapturePhase.REQUESTING_PERMISSION)) return
        transition(MediaCaptureState(kind = "audio", phase = CapturePhase.STARTING))
        val file = File(directory, "${UUID.randomUUID()}.m4a")
        captureFile = file; saved["capturePath"] = file.absolutePath
        try {
            val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(getApplication()) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = next; recordingFile = file
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(22050); next.setAudioEncodingBitRate(48000)
            next.setOutputFile(file.absolutePath)
            next.setMaxDuration(29_500); next.setMaxFileSize(1_048_576)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                    stopVoice("Recording limit reached. Tap Send voice SOS when ready.")
            }
            next.setOnErrorListener { _, _, _ ->
                stopVoice("Recording was interrupted. Review the saved audio before sending.")
            }
            next.prepare(); next.start(); startedAt = SystemClock.elapsedRealtime()
            transition(MediaCaptureState(kind = "audio", phase = CapturePhase.RECORDING))
            timer = viewModelScope.launch {
                while (state.value.recording) {
                    delay(250)
                    mutableState.value = state.value.copy(seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
                }
            }
        } catch (_: Exception) {
            stopAndReleaseRecorder(); file.delete()
            captureFile = null; saved.remove<String>("capturePath")
            transition(MediaCaptureState(kind = "audio", phase = CapturePhase.ERROR,
                notice = "Microphone unavailable. Try recording again or send SOS without media."))
        }
    }

    /** Stops on background/recreation too; finishing never sends a report by itself. */
    fun stopVoice(notice: String? = null) {
        if (!state.value.recording) return
        val file = recordingFile
        var valid = true
        try { recorder?.stop() } catch (_: Exception) { valid = false }
        releaseRecorder()
        if (valid && file != null) finishFile(file, "audio", notice)
        else {
            file?.delete()
            captureFile = null; saved.remove<String>("capturePath")
            transition(MediaCaptureState(kind = "audio", phase = CapturePhase.ERROR,
                notice = "That recording was too short or interrupted. Try again, or send SOS without media."))
        }
    }

    fun beginCamera(kind: String): File {
        require(kind == "image" || kind == "video")
        check(state.value.kind == kind && state.value.phase == CapturePhase.STARTING &&
            state.value.attachment == null && pendingFile == null) { "Another capture is already active." }
        val file = File(directory, "${UUID.randomUUID()}.${if (kind == "image") "jpg" else "mp4"}")
        check(file.createNewFile()) { "Could not prepare the camera file." }
        captureFile = file; saved["capturePath"] = file.absolutePath
        saved["cameraKind"] = kind; saved["cameraPath"] = file.absolutePath
        transition(MediaCaptureState(kind = kind, phase = CapturePhase.WAITING_CAMERA))
        return file
    }

    fun cameraFinished(ok: Boolean) {
        if (state.value.phase != CapturePhase.WAITING_CAMERA) return
        val file = pendingFile ?: return
        val kind = pendingKind ?: return
        clearPendingCamera()
        if (!ok) {
            file.delete(); captureFile = null; saved.remove<String>("capturePath")
            transition(MediaCaptureState(kind = kind, phase = CapturePhase.CANCELLED,
                notice = "No ${if (kind == "image") "photo" else "video"} was captured. Nothing was sent. Try again when ready."))
        } else finishFile(file, kind)
    }

    /** Also accepts app-owned synthetic fixtures for instrumentation without using a real microphone/camera. */
    fun finishFile(file: File, kind: String, notice: String? = null) {
        preparing?.cancel()
        captureFile = file; saved["capturePath"] = file.absolutePath
        transition(MediaCaptureState(kind = kind, phase = CapturePhase.PROCESSING))
        preparing = viewModelScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) { prepareAttachment(file, kind) }
                saved["readyPath"] = attachment.filePath; saved["readyKind"] = kind
                captureFile = File(attachment.filePath); saved["capturePath"] = attachment.filePath
                transition(MediaCaptureState(kind = kind, phase = CapturePhase.READY, attachment = attachment, notice = notice))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) {
                saved.remove<String>("readyPath"); saved.remove<String>("readyKind")
                transition(MediaCaptureState(kind = kind, phase = CapturePhase.ERROR,
                    notice = error.message ?: "Could not save this capture. Send SOS without media or try again."))
            }
        }
    }

    fun discard() {
        preparing?.cancel(); preparing = null
        stopAndReleaseRecorder()
        state.value.attachment?.filePath?.let { File(it).delete() }
        pendingFile?.delete(); recordingFile?.delete(); recordingFile = null
        captureFile?.delete(); captureFile = null
        for (key in listOf("readyPath", "readyKind", "cameraKind", "cameraPath", "capturePath")) saved.remove<String>(key)
        transition(MediaCaptureState())
    }

    fun consumeSaved() {
        // Controller has already copied the bytes into durable per-report storage before success.
        discard()
    }

    private fun releaseRecorder() {
        timer?.cancel(); timer = null
        try { recorder?.release() } catch (_: Exception) { }; recorder = null
    }
    private fun stopAndReleaseRecorder() {
        if (recorder != null) try { recorder?.stop() } catch (_: Exception) { }
        releaseRecorder()
    }
    private fun clearPendingCamera() {
        saved.remove<String>("cameraKind"); saved.remove<String>("cameraPath")
    }
    private fun transition(next: MediaCaptureState) {
        if (next.kind == null) saved.remove<String>("captureKind") else saved["captureKind"] = next.kind
        if (next.phase == CapturePhase.IDLE) saved.remove<String>("capturePhase") else saved["capturePhase"] = next.phase.name
        mutableState.value = next
    }
    override fun onCleared() {
        stopAndReleaseRecorder()
    }

    companion object {
        fun prepareAttachment(file: File, kind: String): DraftAttachment {
            require(file.isFile && file.length() > 0) { "No captured media was saved. Try again or send SOS without media." }
            require(kind in setOf("audio", "image", "video")) { "Unsupported capture type." }
            var output = if (kind == "image") compressPhoto(file) else file
            val limit = if (kind == "video") 8_388_608L else 1_048_576L
            require(output.length() <= limit) { "Capture is too large. Use a shorter recording, take a photo, or send SOS without media." }
            var duration: Long? = null
            if (kind != "image") {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    require(duration != null && duration > 0 && duration <= if (kind == "video") 15_000 else 30_000) {
                        "Capture exceeds the ${if (kind == "video") "15" else "30"}-second limit. Record a shorter message or send SOS without media."
                    }
                    val key = if (kind == "video") MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO else MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                    require(retriever.extractMetadata(key) == "yes") { "Capture could not be played. Try again or send SOS without media." }
                } finally { retriever.release() }
            }
            val originalId = output.nameWithoutExtension.removeSuffix("-ready")
            val id = runCatching { UUID.fromString(originalId).toString() }.getOrNull() ?: UUID.randomUUID().toString().also { replacement ->
                val normalized = File(output.parentFile, "$replacement${if (kind == "image") "-ready" else ""}.${output.extension}")
                require(output.renameTo(normalized)) { "Could not keep this capture on the device. Try again or send SOS without media." }
                output = normalized
            }
            val digest = MessageDigest.getInstance("SHA-256")
            output.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return DraftAttachment(Attachment(id = id, kind = kind, mimeType = when (kind) { "image" -> "image/jpeg"; "audio" -> "audio/mp4"; else -> "video/mp4" },
                byteSize = output.length(), sha256 = digest.digest().joinToString("") { "%02x".format(it) }, durationMs = duration), output.absolutePath)
        }

        private fun compressPhoto(file: File): File {
            // A restored ready JPEG is already bounded and does not need to be encoded again.
            if (file.nameWithoutExtension.endsWith("-ready") && file.length() <= 1_048_576L) return file
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera returned an unreadable photo. Try again or send SOS without media." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Photo could not be opened. Try again or send SOS without media.")
            val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) { val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true); if (rotated !== bitmap) bitmap.recycle(); bitmap = rotated }
            val ratio = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
            if (ratio < 1.0) { val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true); if (scaled !== bitmap) bitmap.recycle(); bitmap = scaled }
            val result = File(file.parentFile, "${file.nameWithoutExtension}-ready.jpg")
            var quality = 82
            try {
                do { result.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }; quality -= 12 } while (result.length() > 1_048_576L && quality >= 22)
            } finally { bitmap.recycle() }
            require(result.length() in 1..1_048_576L) { "Photo could not be compressed. Try again or send SOS without media." }
            file.delete()
            return result
        }
    }
}
