package com.example.japanesegrammarapp.domain.usecase

import android.net.Uri
import com.example.japanesegrammarapp.domain.ApplicationScope
import com.example.japanesegrammarapp.domain.model.AnalysisModule
import com.example.japanesegrammarapp.domain.repository.ImageAttachmentLoader
import com.example.japanesegrammarapp.domain.repository.LlmAnalysisService
import com.example.japanesegrammarapp.domain.repository.OcrRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import com.example.japanesegrammarapp.utils.AppLogger
import com.example.japanesegrammarapp.utils.TextCleaner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class PhotoInputUiStatus(
    val isRunning: Boolean = false,
    val currentTaskIndex: Int = 0,
    val totalTasks: Int = 0,
    val isTyping: Boolean = false,
    val isSkipped: Boolean = false
)

private data class QueuedPhotoTask(
    val index: Int,
    val uri: Uri,
    val deferredResult: CompletableDeferred<String>
)

@Singleton
class PhotoInputQueueManager @Inject constructor(
    private val imageAttachmentLoader: ImageAttachmentLoader,
    private val llmAnalysisService: LlmAnalysisService,
    private val ocrRepository: OcrRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val externalScope: CoroutineScope
) {
    private val _status = MutableStateFlow(PhotoInputUiStatus())
    val status: StateFlow<PhotoInputUiStatus> = _status.asStateFlow()

    private val queuedTasks = mutableListOf<QueuedPhotoTask>()
    private val backgroundJobs = ConcurrentHashMap<Int, Job>()
    private var playbackJob: Job? = null
    @Volatile private var isSkipped = false

    /**
     * Start a new batch of photo inputs. Clears any previous session.
     */
    @Synchronized
    fun startNewBatch() {
        cancelAll()
        queuedTasks.clear()
        isSkipped = false
        _status.value = PhotoInputUiStatus(
            isRunning = false,
            currentTaskIndex = 0,
            totalTasks = 0,
            isTyping = false,
            isSkipped = false
        )
    }

    /**
     * Enqueue a photo URI. Recognition starts immediately in parallel.
     * @return the 0-based index of this task in the batch
     */
    @Synchronized
    fun enqueuePhoto(uri: Uri): Int {
        val index = queuedTasks.size
        val deferred = CompletableDeferred<String>()
        val task = QueuedPhotoTask(index, uri, deferred)
        queuedTasks.add(task)

        _status.update {
            it.copy(
                isRunning = true,
                currentTaskIndex = if (it.currentTaskIndex == 0) 1 else it.currentTaskIndex,
                totalTasks = queuedTasks.size
            )
        }

        // Launch background recognition immediately
        val job = externalScope.launch(Dispatchers.IO) {
            try {
                val text = recognizeImage(uri)
                deferred.complete(text)
            } catch (e: Throwable) {
                AppLogger.e("PHOTO_INPUT", "Recognition failed for index $index", e)
                deferred.complete("")
            } finally {
                backgroundJobs.remove(index)
                // Clean up temporary crop file if it was created in app cache
                cleanupTempFile(uri)
            }
        }
        backgroundJobs[index] = job

        return index
    }

    /**
     * Start sequential playback of recognized text with a typewriter effect.
     * Guaranteed to process strictly in order 0, 1, ..., N-1.
     * Separates consecutive photo texts with a newline `\n`.
     */
    fun startPlayback(
        initialText: String,
        onTextUpdated: (String) -> Unit
    ) {
        playbackJob?.cancel()
        playbackJob = externalScope.launch(Dispatchers.Main) {
            var currentBuffer = initialText
            val total = synchronized(this@PhotoInputQueueManager) { queuedTasks.size }
            
            if (total == 0) {
                _status.value = PhotoInputUiStatus()
                return@launch
            }

            for (i in 0 until total) {
                val task = synchronized(this@PhotoInputQueueManager) {
                    queuedTasks.getOrNull(i)
                } ?: break

                _status.update {
                    it.copy(
                        isRunning = true,
                        currentTaskIndex = i + 1,
                        totalTasks = total,
                        isTyping = false,
                        isSkipped = isSkipped
                    )
                }

                // Wait for this specific task's recognition result (guaranteed sequential output)
                val recognizedText = withContext(Dispatchers.IO) {
                    task.deferredResult.await()
                }

                if (recognizedText.isNotBlank()) {
                    // Separate consecutive photo texts with newline if buffer is not empty
                    if (currentBuffer.isNotEmpty() && !currentBuffer.endsWith("\n")) {
                        currentBuffer += "\n"
                        onTextUpdated(currentBuffer)
                    }

                    _status.update { it.copy(isTyping = true) }

                    if (isSkipped) {
                        currentBuffer += recognizedText
                        onTextUpdated(currentBuffer)
                    } else {
                        // Typewriter character by character
                        for (ch in recognizedText) {
                            if (isSkipped) {
                                // Flushed during typing
                                val remaining = recognizedText.substring(recognizedText.indexOf(ch))
                                currentBuffer += remaining
                                onTextUpdated(currentBuffer)
                                break
                            }
                            currentBuffer += ch
                            onTextUpdated(currentBuffer)
                            delay(28L)
                        }
                    }
                }
            }

            _status.value = PhotoInputUiStatus(isRunning = false)
        }
    }

    /**
     * Skip the typewriter animation and immediately flush all text.
     */
    fun skipAnimation() {
        isSkipped = true
        _status.update { it.copy(isSkipped = true) }
    }

    /**
     * Cancel ongoing recognition and playback.
     */
    fun cancelAll() {
        playbackJob?.cancel()
        playbackJob = null
        backgroundJobs.values.forEach { it.cancel() }
        backgroundJobs.clear()
        isSkipped = false
        _status.value = PhotoInputUiStatus(isRunning = false)
    }

    private suspend fun recognizeImage(uri: Uri): String {
        val uriString = uri.toString()
        val payload = try {
            imageAttachmentLoader.loadAsBase64(uriString)
        } catch (e: Exception) {
            AppLogger.e("PHOTO_INPUT", "Failed to load base64 from $uri", e)
            null
        }

        if (payload != null && payload.base64Data.isNotBlank()) {
            val override = settingsRepository.getComponentModelConfig(AnalysisModule.IMAGE_OCR.id)
            val primaryConfigs = if (!override.isGlobal) {
                val overrideConfigs = settingsRepository.buildLlmApiConfigs(override.provider, override.model)
                if (overrideConfigs.isNotEmpty()) overrideConfigs else {
                    val primaryProvider = settingsRepository.getActiveProvider()
                    val primaryModel = settingsRepository.getActiveModel(primaryProvider)
                    settingsRepository.buildLlmApiConfigs(primaryProvider, primaryModel)
                }
            } else {
                val primaryProvider = settingsRepository.getActiveProvider()
                val primaryModel = settingsRepository.getActiveModel(primaryProvider)
                settingsRepository.buildLlmApiConfigs(primaryProvider, primaryModel)
            }

            val backupProvider = settingsRepository.getBackupProvider()
            val backupModel = settingsRepository.getBackupModel()
            val backupConfigs = if (settingsRepository.getUseBackupApi() && backupProvider.isNotBlank() && backupModel.isNotBlank()) {
                settingsRepository.buildLlmApiConfigs(backupProvider, backupModel)
            } else {
                emptyList()
            }

            if (primaryConfigs.isNotEmpty()) {
                try {
                    var recognized: String? = null
                    llmAnalysisService.executeImageOcr(
                        imageBase64 = payload.base64Data,
                        mimeType = payload.mimeType,
                        primaryConfigs = primaryConfigs,
                        backupConfigs = backupConfigs,
                        onRetry = {},
                        onBackup = {},
                        recordId = null,
                        stepName = "PHOTO_INPUT_OCR"
                    ).collect { pair ->
                        val text = pair.first
                        if (!text.isNullOrBlank()) {
                            recognized = text
                        }
                    }

                    var clean = recognized?.trim().orEmpty()
                    if (clean.isNotBlank()) {
                        if (settingsRepository.getRemoveAccidentalSpaces()) {
                            clean = TextCleaner.removeAccidentalSpaces(clean)
                        }
                        return clean
                    }
                } catch (e: Exception) {
                    AppLogger.e("PHOTO_INPUT", "LLM OCR failed, fallback to local OCR", e)
                }
            }
        }

        // Fallback to local ML Kit OCR
        return try {
            var local = ocrRepository.extractTextFromImage(uri.toString()).trim()
            if (settingsRepository.getRemoveAccidentalSpaces()) {
                local = TextCleaner.removeAccidentalSpaces(local)
            }
            local
        } catch (e: Exception) {
            AppLogger.e("PHOTO_INPUT", "Local OCR also failed", e)
            ""
        }
    }

    private fun cleanupTempFile(uri: Uri) {
        try {
            uri.path?.let { path ->
                val file = File(path)
                if (file.exists() && file.name.startsWith("cropped_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("PHOTO_INPUT", "Failed to cleanup temp file", e)
        }
    }
}
