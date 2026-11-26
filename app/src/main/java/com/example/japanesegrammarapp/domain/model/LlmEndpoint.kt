package com.example.japanesegrammarapp.domain.model

data class LlmEndpoint(
    val id: String,
    val provider: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val weight: Int = 1
)
