package com.example.japanesegrammarapp.data.repository

import com.example.japanesegrammarapp.domain.model.EndpointHealthState
import com.example.japanesegrammarapp.domain.repository.EndpointHealthTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryEndpointHealthTracker @Inject constructor() : EndpointHealthTracker {

    private val states = ConcurrentHashMap<String, EndpointHealthState>()
    private val _healthStates = MutableStateFlow<Map<String, EndpointHealthState>>(emptyMap())
    override val healthStates: StateFlow<Map<String, EndpointHealthState>> = _healthStates.asStateFlow()

    override fun getHealth(endpointId: String): EndpointHealthState {
        if (endpointId.isBlank()) return EndpointHealthState.HEALTHY
        return states[endpointId] ?: EndpointHealthState.HEALTHY
    }

    override fun touch(endpointId: String, nowMs: Long) {
        if (endpointId.isBlank()) return
        updateState(endpointId) { current ->
            current.copy(lastUsedAtMs = nowMs)
        }
    }

    override fun markSuccess(endpointId: String, nowMs: Long) {
        if (endpointId.isBlank()) return
        updateState(endpointId) {
            EndpointHealthState(
                cooldownUntilMs = 0L,
                consecutiveFailures = 0,
                lastUsedAtMs = nowMs,
                lastError = null
            )
        }
    }

    override fun markFailure(endpointId: String, error: String, cooldownMs: Long, nowMs: Long) {
        if (endpointId.isBlank()) return
        updateState(endpointId) { current ->
            current.copy(
                cooldownUntilMs = nowMs + cooldownMs.coerceAtLeast(0L),
                consecutiveFailures = current.consecutiveFailures + 1,
                lastUsedAtMs = nowMs,
                lastError = error
            )
        }
    }

    override fun clear(endpointId: String) {
        if (endpointId.isBlank()) return
        if (states.remove(endpointId) != null) {
            publishSnapshot()
        }
    }

    private fun updateState(
        endpointId: String,
        transform: (EndpointHealthState) -> EndpointHealthState
    ) {
        states.compute(endpointId) { _, existing ->
            transform(existing ?: EndpointHealthState.HEALTHY)
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _healthStates.value = states.toMap()
    }
}
