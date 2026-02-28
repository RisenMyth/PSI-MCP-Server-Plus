package com.github.risenmyth.psimcpserverplus.mcp

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PsiMcpObservability {
    private val httpRequestTotal = AtomicLong(0)
    private val httpRequestsByMethod = ConcurrentHashMap<String, AtomicLong>()
    private val httpResponsesByStatus = ConcurrentHashMap<Int, AtomicLong>()

    private val rpcCallsByMethod = ConcurrentHashMap<String, AtomicLong>()
    private val rpcErrorsByCode = ConcurrentHashMap<Int, AtomicLong>()

    private val sessionsCreated = AtomicLong(0)
    private val sessionsRemoved = AtomicLong(0)
    private val sessionsExpired = AtomicLong(0)

    private val sseConnectionsOpened = AtomicLong(0)
    private val sseConnectionsClosed = AtomicLong(0)
    private val sseRejectedByReason = ConcurrentHashMap<String, AtomicLong>()
    private val sseEventsByType = ConcurrentHashMap<String, AtomicLong>()

    fun recordHttpRequest(method: String) {
        httpRequestTotal.incrementAndGet()
        increment(httpRequestsByMethod, method.uppercase(Locale.ROOT))
    }

    fun recordHttpResponse(status: Int) {
        increment(httpResponsesByStatus, status)
    }

    fun recordRpcCall(method: String) {
        increment(rpcCallsByMethod, method)
    }

    fun recordRpcError(code: Int) {
        increment(rpcErrorsByCode, code)
    }

    fun recordSessionCreated() {
        sessionsCreated.incrementAndGet()
    }

    fun recordSessionsRemoved(count: Int, expired: Boolean) {
        if (count <= 0) {
            return
        }
        sessionsRemoved.addAndGet(count.toLong())
        if (expired) {
            sessionsExpired.addAndGet(count.toLong())
        }
    }

    fun recordSseConnectionOpened() {
        sseConnectionsOpened.incrementAndGet()
    }

    fun recordSseConnectionClosed() {
        sseConnectionsClosed.incrementAndGet()
    }

    fun recordSseRejected(reason: String) {
        increment(sseRejectedByReason, reason)
    }

    fun recordSseEvent(eventType: String) {
        increment(sseEventsByType, eventType)
    }

    fun snapshot(sessionSnapshot: PsiMcpSessionSnapshot): PsiMcpObservabilitySnapshot {
        return PsiMcpObservabilitySnapshot(
            httpRequestTotal = httpRequestTotal.get(),
            httpRequestsByMethod = snapshotStringCounters(httpRequestsByMethod),
            httpResponsesByStatus = snapshotIntCounters(httpResponsesByStatus),
            rpcCallsByMethod = snapshotStringCounters(rpcCallsByMethod),
            rpcErrorsByCode = snapshotIntCounters(rpcErrorsByCode),
            sessionsCreated = sessionsCreated.get(),
            sessionsRemoved = sessionsRemoved.get(),
            sessionsExpired = sessionsExpired.get(),
            sseConnectionsOpened = sseConnectionsOpened.get(),
            sseConnectionsClosed = sseConnectionsClosed.get(),
            sseRejectedByReason = snapshotStringCounters(sseRejectedByReason),
            sseEventsByType = snapshotStringCounters(sseEventsByType),
            sessionSnapshot = sessionSnapshot,
        )
    }

    private fun increment(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    private fun increment(map: ConcurrentHashMap<Int, AtomicLong>, key: Int) {
        map.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    private fun snapshotStringCounters(map: ConcurrentHashMap<String, AtomicLong>): Map<String, Long> {
        return map.entries
            .map { it.key to it.value.get() }
            .sortedBy { it.first }
            .toMap(LinkedHashMap())
    }

    private fun snapshotIntCounters(map: ConcurrentHashMap<Int, AtomicLong>): Map<Int, Long> {
        return map.entries
            .map { it.key to it.value.get() }
            .sortedBy { it.first }
            .toMap(LinkedHashMap())
    }
}

data class PsiMcpSessionSnapshot(
    val total: Int,
    val initialized: Int,
    val streamAttached: Int,
    val closed: Int,
    val queuedEvents: Int,
    val droppedEvents: Long,
) {
    fun toLogFragment(): String {
        return "session={total=$total initialized=$initialized streamAttached=$streamAttached closed=$closed queuedEvents=$queuedEvents droppedEvents=$droppedEvents}"
    }
}

data class PsiMcpObservabilitySnapshot(
    val httpRequestTotal: Long,
    val httpRequestsByMethod: Map<String, Long>,
    val httpResponsesByStatus: Map<Int, Long>,
    val rpcCallsByMethod: Map<String, Long>,
    val rpcErrorsByCode: Map<Int, Long>,
    val sessionsCreated: Long,
    val sessionsRemoved: Long,
    val sessionsExpired: Long,
    val sseConnectionsOpened: Long,
    val sseConnectionsClosed: Long,
    val sseRejectedByReason: Map<String, Long>,
    val sseEventsByType: Map<String, Long>,
    val sessionSnapshot: PsiMcpSessionSnapshot,
) {
    fun toLogLine(): String {
        return buildString {
            append("http.requests=").append(httpRequestTotal)
            append(" http.byMethod=").append(httpRequestsByMethod)
            append(" http.byStatus=").append(httpResponsesByStatus)
            append(" rpc.byMethod=").append(rpcCallsByMethod)
            append(" rpc.errors=").append(rpcErrorsByCode)
            append(" sessions.created=").append(sessionsCreated)
            append(" sessions.removed=").append(sessionsRemoved)
            append(" sessions.expired=").append(sessionsExpired)
            append(" sse.opened=").append(sseConnectionsOpened)
            append(" sse.closed=").append(sseConnectionsClosed)
            append(" sse.rejected=").append(sseRejectedByReason)
            append(" sse.events=").append(sseEventsByType)
            append(' ')
            append(sessionSnapshot.toLogFragment())
        }
    }
}
