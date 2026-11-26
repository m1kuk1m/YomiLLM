package com.example.japanesegrammarapp.domain.repository

import com.example.japanesegrammarapp.domain.model.EndpointHealthState
import kotlinx.coroutines.flow.StateFlow

interface EndpointHealthTracker {
    val healthStates: StateFlow<Map<String, EndpointHealthState>>

    fun getHealth(endpointId: String): EndpointHealthState

    fun touch(endpointId: String, nowMs: Long = System.currentTimeMillis())

    fun markSuccess(endpointId: String, nowMs: Long = System.currentTimeMillis())

    fun markFailure(
        endpointId: String,
        error: String,
        cooldownMs: Long,
        nowMs: Long = System.currentTimeMillis()
    )

    fun clear(endpointId: String)
}
