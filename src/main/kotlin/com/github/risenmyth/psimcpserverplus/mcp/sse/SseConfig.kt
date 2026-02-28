package com.github.risenmyth.psimcpserverplus.mcp.sse

class SseConfig(
    val queueSize: Int = getQueueSize(),
    val sessionTimeoutMs: Long = getSessionTimeoutMs(),
    val heartbeatIntervalMs: Long = getHeartbeatIntervalMs(),
    val retryTimeoutMs: Long = getRetryTimeoutMs(),
    val keepAliveSeconds: Long = SseConstants.DEFAULT_KEEP_ALIVE_SECONDS,
) {
    companion object {
        private fun getSystemPropertyLong(name: String, default: Long): Long {
            return System.getProperty(name)?.toLongOrNull() ?: default
        }

        fun getQueueSize(): Int = getSystemPropertyLong(
            SseConstants.PROP_QUEUE_SIZE,
            SseConstants.DEFAULT_QUEUE_SIZE.toLong(),
        ).toInt().coerceAtLeast(1)

        fun getSessionTimeoutMs(): Long = getSystemPropertyLong(
            SseConstants.PROP_SESSION_TIMEOUT,
            SseConstants.DEFAULT_SESSION_TIMEOUT_MINUTES,
        ) * 60 * 1000

        fun getHeartbeatIntervalMs(): Long = getSystemPropertyLong(
            SseConstants.PROP_HEARTBEAT_INTERVAL,
            SseConstants.DEFAULT_HEARTBEAT_INTERVAL_MINUTES,
        ) * 60 * 1000

        fun getRetryTimeoutMs(): Long = getSystemPropertyLong(
            SseConstants.PROP_RETRY_TIMEOUT,
            SseConstants.DEFAULT_RETRY_TIMEOUT_MS,
        )
    }
}
