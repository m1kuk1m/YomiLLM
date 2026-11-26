package com.example.japanesegrammarapp.domain.usecase

import android.net.Uri
import com.example.japanesegrammarapp.domain.repository.ImageOcrStreamChunk
import com.example.japanesegrammarapp.domain.repository.ImageTextExtractor
import com.example.japanesegrammarapp.domain.repository.LlmApiConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    private class FakeImageTextExtractor(
        private val textProvider: suspend (String) -> String = { "local_ocr_text" }
    ) : ImageTextExtractor {
        override suspend fun extractTextLocal(imageUri: String, cleanSpaces: Boolean): String {
            return textProvider(imageUri)
        }

        override fun extractTextWithVisionStreaming(
            imageBase64: String,
            mimeType: String?,
            primaryConfigs: List<LlmApiConfig>,
            backupConfigs: List<LlmApiConfig>,
            onRetry: (attempt: Int) -> Unit,
            onBackup: (backupProvider: String) -> Unit,
            recordId: Int?,
            stepName: String?
        ): Flow<ImageOcrStreamChunk> = emptyFlow()

        override suspend fun extractTextPreferVisionWithLocalFallback(
            imageUri: String,
            stepName: String
        ): String {
            return textProvider(imageUri)
        }
    }

    @Test
    fun testSequentialOrderPreservedEvenWhenSecondFinishesFirst() = runTest(testDispatcher) {
        val testScope = this

        val page1Deferred = CompletableDeferred<String>()
        val page2Deferred = CompletableDeferred<String>()

        val extractor = FakeImageTextExtractor { uriStr ->
            if (uriStr.contains("page1")) {
                page1Deferred.await()
            } else {
                page2Deferred.await()
            }
        }

        val manager = PhotoInputQueueManager(
            imageTextExtractor = extractor,
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

        val extractor = FakeImageTextExtractor { uriStr ->
            if (uriStr.contains("shot1")) "Hello" else "World"
        }

        val manager = PhotoInputQueueManager(
            imageTextExtractor = extractor,
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
            imageTextExtractor = FakeImageTextExtractor { "Text" },
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
            imageTextExtractor = FakeImageTextExtractor { "Text" },
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
