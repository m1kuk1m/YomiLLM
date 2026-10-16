package com.example.japanesegrammarapp.ui

import androidx.lifecycle.ViewModel
import com.example.japanesegrammarapp.domain.repository.UiPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

import com.example.japanesegrammarapp.domain.usecase.PhotoInputQueueManager
import android.net.Uri

@HiltViewModel
class CameraViewModel @Inject constructor(
    val uiPreferencesRepository: UiPreferencesRepository,
    val photoInputQueueManager: PhotoInputQueueManager
) : ViewModel() {

    fun startPhotoInputBatch() {
        photoInputQueueManager.startNewBatch()
    }

    fun enqueuePhotoInput(uri: Uri): Int {
        return photoInputQueueManager.enqueuePhoto(uri)
    }

    fun cancelPhotoInput() {
        photoInputQueueManager.cancelAll()
    }
}
