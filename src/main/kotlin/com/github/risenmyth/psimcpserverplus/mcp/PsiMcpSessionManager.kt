package com.github.risenmyth.psimcpserverplus.mcp

import com.github.risenmyth.psimcpserverplus.mcp.sse.SseConfig
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseSessionState
import com.intellij.openapi.diagnostic.thisLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PsiMcpSessionManager(
    private val router: PsiMcpProjectRouter,
    private val sseConfig: SseConfig,
    private val observability: PsiMcpObservability? = null,
) {
    private val sessions = ConcurrentHashMap<String, SseSessionState>()

    fun createSession(projectPath: String): SseSessionState {
        val sessionId = UUID.randomUUID().toString()
        return SseSessionState(sessionId, projectPath, sseConfig.queueSize).also { created ->
            sessions[sessionId] = created
            observability?.recordSessionCreated()
            thisLogger().debug("Session created: id=${created.id}, projectPath=${created.projectPath}")
        }
    }

    fun getSession(sessionId: String?): SseSessionState? {
        if (sessionId.isNullOrBlank()) {
            return null
        }
        return sessions[sessionId]
    }

    fun removeSession(sessionId: String?): SseSessionState? {
        if (sessionId.isNullOrBlank()) {
            return null
        }
        return sessions.remove(sessionId)?.also {
            it.close()
            observability?.recordSessionsRemoved(count = 1, expired = false)
            thisLogger().debug("Session removed: id=${it.id}")
        }
    }

    fun sessionMap(): ConcurrentHashMap<String, SseSessionState> = sessions

    fun clearAll() {
        val existing = sessions.values.toList()
        existing.forEach { it.close() }
        sessions.clear()
        observability?.recordSessionsRemoved(count = existing.size, expired = false)
        if (existing.isNotEmpty()) {
            thisLogger().debug("All sessions cleared: count=${existing.size}")
        }
    }

    fun cleanupExpiredSessions(timeoutMs: Long): Int {
        var removed = 0
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(timeoutMs)) {
                entry.value.close()
                iterator.remove()
                removed++
            }
        }
        observability?.recordSessionsRemoved(count = removed, expired = true)
        return removed
    }

    fun snapshot(): PsiMcpSessionSnapshot {
        val current = sessions.values.toList()
        var initialized = 0
        var streamAttached = 0
        var closed = 0
        var queuedEvents = 0
        var droppedEvents = 0L

        for (session in current) {
            if (session.isInitialized) {
                initialized++
            }
            if (session.streamAttached()) {
                streamAttached++
            }
            if (session.isClosed()) {
                closed++
            }
            queuedEvents += session.queueSize()
            droppedEvents += session.droppedCount()
        }

        return PsiMcpSessionSnapshot(
            total = current.size,
            initialized = initialized,
            streamAttached = streamAttached,
            closed = closed,
            queuedEvents = queuedEvents,
            droppedEvents = droppedEvents,
        )
    }

    fun validateProjectPathHeader(projectPathHeader: String?, session: SseSessionState): String? {
        if (projectPathHeader == null) {
            return null
        }
        val normalizedHeader = router.normalizeProjectPath(projectPathHeader)
            ?: return "Invalid PROJECT_PATH"
        if (normalizedHeader != session.projectPath) {
            return "PROJECT_PATH does not match session-bound project"
        }
        return null
    }
}
