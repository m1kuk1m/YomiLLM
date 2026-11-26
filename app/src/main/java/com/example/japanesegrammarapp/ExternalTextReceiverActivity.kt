package com.example.japanesegrammarapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.japanesegrammarapp.domain.usecase.ExternalTextIntakeResult
import com.example.japanesegrammarapp.domain.usecase.ExternalTextIntakeUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ExternalTextReceiverActivity : AppCompatActivity() {

    @Inject
    lateinit var externalTextIntakeUseCase: ExternalTextIntakeUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawText = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        if (rawText.isNullOrBlank()) {
            finishWithoutAnimation()
            return
        }

        lifecycleScope.launch {
            when (val result = externalTextIntakeUseCase.handle(rawText)) {
                ExternalTextIntakeResult.Ignored -> {
                    withContext(Dispatchers.Main) {
                        finishWithoutAnimation()
                    }
                }
                is ExternalTextIntakeResult.QueuedSilently -> {
                    withContext(Dispatchers.Main) {
                        val displayText = if (result.trimmedText.length > 15) {
                            result.trimmedText.take(15) + "..."
                        } else {
                            result.trimmedText
                        }
                        val toastMsg = getString(R.string.silent_analysis_queued_toast, displayText)
                        Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
                        finishWithoutAnimation()
                    }
                }
                is ExternalTextIntakeResult.OpenInWorkspace -> {
                    withContext(Dispatchers.Main) {
                        val mainIntent = Intent(this@ExternalTextReceiverActivity, MainActivity::class.java).apply {
                            putExtra(MainActivity.EXTRA_EXTERNAL_ANALYSIS_TEXT, result.trimmedText)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(mainIntent)
                        finishWithoutAnimation()
                    }
                }
            }
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }
}
