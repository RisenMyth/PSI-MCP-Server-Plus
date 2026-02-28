package com.github.risenmyth.psimcpserverplus.mcp.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedWriter

class SseEventWriter(
    private val writer: BufferedWriter,
    private val retryTimeoutMs: Long,
) {
    fun sendInitialHandshake() {
        writer.write("retry: $retryTimeoutMs\n\n")
        writer.flush()
    }

    fun sendEvent(
        eventType: String = SseConstants.EVENT_MESSAGE,
        eventId: Long,
        data: String,
    ) {
        writer.write("event: $eventType\n")
        writer.write("id: $eventId\n")
        SseEventFormatter.formatDataLines(data).forEach { line ->
            writer.write("data: $line\n")
        }
        writer.write("\n")
        writer.flush()
    }

    fun sendKeepAlive() {
        writer.write(": ping\n\n")
        writer.flush()
    }

    fun sendError(eventId: Long, errorCode: Int, message: String, details: String? = null) {
        val errorJson = buildJsonObject {
            put("code", JsonPrimitive(errorCode))
            put("message", JsonPrimitive(message))
            if (details != null) {
                put("details", JsonPrimitive(details))
            }
        }
        val errorText = Json { prettyPrint = false }.encodeToString(errorJson)
        sendEvent(SseConstants.EVENT_ERROR, eventId = eventId, data = errorText)
    }
}
