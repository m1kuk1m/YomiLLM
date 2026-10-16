package com.example.japanesegrammarapp.domain.usecase

import android.net.Uri
import com.example.japanesegrammarapp.domain.model.ComponentModelConfig
import com.example.japanesegrammarapp.domain.repository.ImageAttachmentLoader
import com.example.japanesegrammarapp.domain.repository.ImagePayload
import com.example.japanesegrammarapp.domain.repository.LlmAnalysisService
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import com.example.japanesegrammarapp.domain.repository.LlmResultMetadata
import com.example.japanesegrammarapp.domain.repository.OcrRepository
import com.example.japanesegrammarapp.domain.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoInputQueueManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeImageAttachmentLoader : ImageAttachmentLoader {
        override suspend fun loadAsBase64(uriString: String): ImagePayload? {
            return ImagePayload("fake_base64_$uriString", "image/jpeg")
        }
    }

    private class FakeOcrRepository(
        private val localTextProvider: suspend (String) -> String = { "local_ocr_text" }
    ) : OcrRepository {
        override suspend fun extractTextFromImage(imageUri: String): String {
            return localTextProvider(imageUri)
        }
    }

    private class FakeSettingsRepository : SettingsRepository by mock(SettingsRepository::class.java) {
        override fun getRemoveAccidentalSpaces(): Boolean = false
        override fun getComponentModelConfig(apiTypeLabel: String): ComponentModelConfig = ComponentModelConfig()
        override fun getActiveProvider(): String = "mock"
        override fun getActiveModel(provider: String): String = "mock"
        override fun buildLlmApiConfigs(provider: String, modelName: String): List<LlmApiConfig> = emptyList() // Fallback to local OCR
        override fun getUseBackupApi(): Boolean = false
        override fun getBackupProvider(): String = ""
        override fun getBackupModel(): String = ""
    }

    @Test
    fun testSequentialOrderPreservedEvenWhenSecondFinishesFirst() = runTest(testDispatcher) {
        val testScope = this

        val page1Deferred = CompletableDeferred<String>()
        val page2Deferred = CompletableDeferred<String>()

        val localOcr = FakeOcrRepository { uriStr ->
            if (uriStr.contains("page1")) {
                page1Deferred.await()
            } else {
                page2Deferred.await()
            }
        }

        val manager = PhotoInputQueueManager(
            imageAttachmentLoader = FakeImageAttachmentLoader(),
            llmAnalysisService = mock(LlmAnalysisService::class.java),
            ocrRepository = localOcr,
            settingsRepository = FakeSettingsRepository(),
            externalScope = testScope
        )

        manager.startNewBatch()
        val uri1 = mock(Uri::class.java).apply {
            org.mockito.Mockito.`when`(toString()).thenReturn("file://page1.jpg")
            org.mockito.Mockito.`when`(path).thenReturn("/page1.jpg")
        }
        val uri2 = mock(Uri::class.java).apply {
            org.mockito.Mockito.`when`(toString()).thenReturn("file://page2.jpg")
            org.mockito.Mockito.`when`(path).thenReturn("/page2.jpg")
        }

        manager.enqueuePhoto(uri1)
        manager.enqueuePhoto(uri2)

        manager.skipAnimation() // skip delay for fast test

        var finalResult = ""
        manager.startPlayback(initialText = "") { updated ->
            finalResult = updated
        }

        // Complete page2 first
        page2Deferred.complete("二ページ目の文章。")
        var waitCount = 0
        while (waitCount < 5) {
            delay(20)
            waitCount++
        }

        // Page 1 hasn't completed yet, so playback must NOT have outputted page 2
        assertEquals("", finalResult)

        // Now complete page 1
        page1Deferred.complete("一ページ目の文章。")

        var retries = 0
        while (manager.status.value.isRunning && retries < 100) {
            delay(50)
            retries++
        }

        assertEquals("一ページ目の文章。\n二ページ目の文章。", finalResult)
        assertFalse(manager.status.value.isRunning)
    }

    @Test
    fun testNewlineSeparatesConsecutivePhotoTexts() = runTest(testDispatcher) {
        val testScope = this

        val localOcr = FakeOcrRepository { uriStr ->
            if (uriStr.contains("shot1")) "Hello" else "World"
        }

        val manager = PhotoInputQueueManager(
            imageAttachmentLoader = FakeImageAttachmentLoader(),
            llmAnalysisService = mock(LlmAnalysisService::class.java),
            ocrRepository = localOcr,
            settingsRepository = FakeSettingsRepository(),
            externalScope = testScope
        )

        manager.startNewBatch()
        val uri1 = mock(Uri::class.java).apply {
            org.mockito.Mockito.`when`(toString()).thenReturn("file://shot1.jpg")
            org.mockito.Mockito.`when`(path).thenReturn("/shot1.jpg")
        }
        val uri2 = mock(Uri::class.java).apply {
            org.mockito.Mockito.`when`(toString()).thenReturn("file://shot2.jpg")
            org.mockito.Mockito.`when`(path).thenReturn("/shot2.jpg")
        }

        manager.enqueuePhoto(uri1)
        manager.enqueuePhoto(uri2)
        manager.skipAnimation()

        var result = ""
        manager.startPlayback(initialText = "Prefix") { result = it }

        var retries = 0
        while (manager.status.value.isRunning && retries < 50) {
            delay(50)
            retries++
        }

        assertEquals("Prefix\nHello\nWorld", result)
    }

    @Test
    fun testCancelAllResetsStatus() = runTest(testDispatcher) {
        val testScope = this

        val manager = PhotoInputQueueManager(
            imageAttachmentLoader = FakeImageAttachmentLoader(),
            llmAnalysisService = mock(LlmAnalysisService::class.java),
            ocrRepository = FakeOcrRepository { "Text" },
            settingsRepository = FakeSettingsRepository(),
            externalScope = testScope
        )

        manager.startNewBatch()
        val uri = mock(Uri::class.java).apply {
            org.mockito.Mockito.`when`(toString()).thenReturn("file://shot.jpg")
            org.mockito.Mockito.`when`(path).thenReturn("/shot.jpg")
        }
        manager.enqueuePhoto(uri)
        assertTrue(manager.status.value.isRunning)

        manager.cancelAll()
        assertFalse(manager.status.value.isRunning)
    }

    @Test
    fun testStartNewBatchDoesNotShowRunningUntilPhotoEnqueued() = runTest(testDispatcher) {
        val testScope = this

        val manager = PhotoInputQueueManager(
            imageAttachmentLoader = FakeImageAttachmentLoader(),
            llmAnalysisService = mock(LlmAnalysisService::class.java),
            ocrRepository = FakeOcrRepository { "Text" },
            settingsRepository = FakeSettingsRepository(),
            externalScope = testScope
        )

        manager.startNewBatch()
        assertFalse(manager.status.value.isRunning)
        assertEquals(0, manager.status.value.totalTasks)

        // Starting playback with 0 enqueued tasks must also remain not running
        manager.startPlayback(initialText = "") {}
        assertFalse(manager.status.value.isRunning)
    }
}
