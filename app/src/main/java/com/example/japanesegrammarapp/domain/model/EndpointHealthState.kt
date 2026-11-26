package com.example.japanesegrammarapp.domain.model

data class EndpointHealthState(
    val cooldownUntilMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastUsedAtMs: Long = 0L,
    val lastError: String? = null
) {
    fun isCoolingDown(nowMs: Long = System.currentTimeMillis()): Boolean =
        cooldownUntilMs > nowMs

    companion object {
        val HEALTHY = EndpointHealthState()
    }
}
