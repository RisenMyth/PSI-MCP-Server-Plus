package com.github.risenmyth.psimcpserverplus.mcp.sse

import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PsiMcpSseHttpHandler(
    private val sessions: ConcurrentHashMap<String, SseSessionState>,
    private val sseConfig: SseConfig,
    private val validateProjectPathHeader: (String?, SseSessionState) -> String?,
    private val isOriginAllowed: (Headers) -> Boolean,
    private val writeStatus: (HttpExchange, Int) -> Unit,
    private val writeJsonError: (HttpExchange, Int, Int, String) -> Unit,
    private val isServerRunning: () -> Boolean,
    private val httpBadRequest: Int,
    private val httpNotFound: Int,
    private val httpConflict: Int,
    private val httpNotAcceptable: Int,
    private val httpMethodNotAllowed: Int,
    private val errorInvalidRequest: Int,
    private val errorInternal: Int,
    private val errorSessionNotFound: Int,
    private val errorProjectMismatch: Int,
) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        val requestId = UUID.randomUUID().toString().substring(0, 8)
        thisLogger().debug("[$requestId] SSE connection request")

        try {
            if (!isOriginAllowed(exchange.requestHeaders)) {
                writeJsonError(exchange, 403, errorInvalidRequest, "Forbidden origin")
                return
            }

            when (exchange.requestMethod.uppercase(Locale.ROOT)) {
                "GET" -> handleSseGet(exchange, requestId)
                else -> {
                    exchange.responseHeaders.add("Allow", "GET")
                    writeStatus(exchange, httpMethodNotAllowed)
                }
            }
        } catch (t: Throwable) {
            thisLogger().warn("[$requestId] Failed to process SSE request", t)
            writeJsonError(exchange, 500, errorInternal, "Internal error")
        }
    }

    private fun handleSseGet(exchange: HttpExchange, requestId: String) {
        val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
        val projectPathHeader = exchange.requestHeaders.firstValue("PROJECT_PATH")

        if (!acceptsSse(exchange.requestHeaders)) {
            thisLogger().debug("[$requestId] Unsupported Accept header for SSE request")
            writeJsonError(exchange, httpNotAcceptable, errorInvalidRequest, "Accept must include text/event-stream")
            return
        }

        if (sessionId.isNullOrBlank()) {
            thisLogger().debug("[$requestId] Missing session ID header")
            writeJsonError(exchange, httpBadRequest, errorInvalidRequest, "Missing MCP-Session-Id header")
            return
        }

        val session = sessions[sessionId]
        if (session == null) {
            thisLogger().debug("[$requestId] Session not found: $sessionId")
            writeJsonError(exchange, httpNotFound, errorSessionNotFound, "Session not found")
            return
        }
        if (session.isClosed()) {
            thisLogger().debug("[$requestId] Session already closed: $sessionId")
            writeJsonError(exchange, httpNotFound, errorSessionNotFound, "Session closed")
            return
        }

        val mismatchError = validateProjectPathHeader(projectPathHeader, session)
        if (mismatchError != null) {
            thisLogger().debug("[$requestId] PROJECT_PATH mismatch for session: $sessionId")
            writeJsonError(exchange, httpConflict, errorProjectMismatch, mismatchError)
            return
        }

        val lastEventIdHeader = exchange.requestHeaders.firstValue("Last-Event-ID")
        val lastEventId = lastEventIdHeader?.toLongOrNull()
        if (lastEventIdHeader != null && lastEventId == null) {
            thisLogger().debug("[$requestId] Invalid Last-Event-ID header: $lastEventIdHeader")
            writeJsonError(exchange, httpBadRequest, errorInvalidRequest, "Invalid Last-Event-ID header")
            return
        }

        session.updateActivity()

        if (!session.attachStream()) {
            thisLogger().debug("[$requestId] Stream already attached for session: $sessionId")
            writeJsonError(exchange, httpConflict, errorInvalidRequest, "Stream already attached")
            return
        }

        thisLogger().info("[$requestId] SSE connected for session: $sessionId")

        applySseHeaders(exchange.responseHeaders)
        exchange.sendResponseHeaders(200, 0)

        try {
            exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                val sseWriter = SseEventWriter(writer, sseConfig.retryTimeoutMs)
                sseWriter.sendInitialHandshake()
                session.updateActivity()

                val missedEvents = session.getEventsAfter(lastEventId)
                for ((eventId, eventPayload) in missedEvents) {
                    sseWriter.sendEvent(
                        SseConstants.EVENT_MESSAGE,
                        eventId,
                        SseEventFormatter.escapeJsonNewlines(eventPayload),
                    )
                    session.updateActivity()
                }

                if (missedEvents.isEmpty() && lastEventId == null) {
                    val eventId = session.nextEventId()
                    val initialPayload = "{}"
                    session.recordEvent(eventId, initialPayload)
                    sseWriter.sendEvent(SseConstants.EVENT_MESSAGE, eventId, initialPayload)
                    session.updateActivity()
                }

                val keepAliveTimeout = sseConfig.keepAliveSeconds
                var lastKeepAlive = System.currentTimeMillis()

                while (session.streamAttached() && isServerRunning()) {
                    if (sessions[sessionId] == null) {
                        sseWriter.sendError(
                            eventId = session.nextEventId(),
                            errorCode = errorSessionNotFound,
                            message = "Session not found",
                        )
                        break
                    }

                    if (session.isExpired(sseConfig.sessionTimeoutMs)) {
                        sseWriter.sendError(
                            eventId = session.nextEventId(),
                            errorCode = errorSessionNotFound,
                            message = "Session expired",
                        )
                        sessions.remove(sessionId, session)
                        break
                    }

                    val runtimeMismatch = validateProjectPathHeader(projectPathHeader, session)
                    if (runtimeMismatch != null) {
                        sseWriter.sendError(
                            eventId = session.nextEventId(),
                            errorCode = errorProjectMismatch,
                            message = "PROJECT_PATH does not match session-bound project",
                            details = runtimeMismatch,
                        )
                        break
                    }

                    try {
                        val payload = session.pollEvent(keepAliveTimeout, TimeUnit.SECONDS)
                        val now = System.currentTimeMillis()

                        if (payload != null) {
                            if (payload == SseSessionState.TERMINATION_EVENT) {
                                break
                            }
                            val eventId = session.nextEventId()
                            session.recordEvent(eventId, payload)
                            sseWriter.sendEvent(
                                SseConstants.EVENT_MESSAGE,
                                eventId,
                                SseEventFormatter.escapeJsonNewlines(payload),
                            )
                            session.updateActivity()
                            lastKeepAlive = now
                        } else {
                            if (now - lastKeepAlive >= keepAliveTimeout * 1000) {
                                sseWriter.sendKeepAlive()
                                session.updateActivity()
                                lastKeepAlive = now
                            }
                        }
                    } catch (e: java.io.IOException) {
                        thisLogger().info("[$requestId] Client disconnected (IOException): ${e.message}")
                        break
                    }
                }
            }
        } finally {
            session.detachStream()
            thisLogger().info(
                "[$requestId] SSE disconnected for session: $sessionId (queueSize=${session.queueSize()}, dropped=${session.droppedCount()})",
            )
        }
    }

    private fun acceptsSse(headers: Headers): Boolean {
        val accept = headers.firstValue("Accept") ?: return true
        val lower = accept.lowercase(Locale.ROOT)
        return lower.contains("text/event-stream") || lower.contains("*/*") || lower.contains("text/*")
    }

    private fun applySseHeaders(headers: Headers) {
        headers.add("Content-Type", SseConstants.CONTENT_TYPE)
        headers.add("Cache-Control", SseConstants.CACHE_CONTROL)
        headers.add("Connection", SseConstants.CONNECTION)
        headers.add("X-Accel-Buffering", SseConstants.X_ACCEL_BUFFERING)
    }

    private fun Headers.firstValue(name: String): String? {
        return this.entries.firstOrNull { (k, _) -> k.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }
}
