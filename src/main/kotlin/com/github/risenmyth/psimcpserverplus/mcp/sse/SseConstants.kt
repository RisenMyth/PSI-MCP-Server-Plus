package com.github.risenmyth.psimcpserverplus.mcp.sse

object SseConstants {
    const val DEFAULT_QUEUE_SIZE = 1000
    const val DEFAULT_SESSION_TIMEOUT_MINUTES = 30L
    const val DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 5L
    const val DEFAULT_RETRY_TIMEOUT_MS = 3000L
    const val DEFAULT_KEEP_ALIVE_SECONDS = 15L
    const val EVENT_HISTORY_SIZE = 100

    const val CONTENT_TYPE = "text/event-stream; charset=utf-8"
    const val CACHE_CONTROL = "no-cache"
    const val CONNECTION = "keep-alive"
    const val X_ACCEL_BUFFERING = "no"

    const val EVENT_MESSAGE = "message"
    const val EVENT_ERROR = "error"

    const val PROP_QUEUE_SIZE = "psimcp.sse.queueSize"
    const val PROP_SESSION_TIMEOUT = "psimcp.sse.sessionTimeout"
    const val PROP_HEARTBEAT_INTERVAL = "psimcp.sse.heartbeatInterval"
    const val PROP_RETRY_TIMEOUT = "psimcp.sse.retryTimeout"
}
