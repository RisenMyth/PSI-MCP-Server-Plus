package com.github.risenmyth.psimcpserverplus.mcp

import com.github.risenmyth.psimcpserverplus.mcp.sse.SseConfig
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseSessionState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PsiMcpSessionManager(
    private val router: PsiMcpProjectRouter,
    private val sseConfig: SseConfig,
) {
    private val sessions = ConcurrentHashMap<String, SseSessionState>()

    fun createSession(projectPath: String): SseSessionState {
        val sessionId = UUID.randomUUID().toString()
        return SseSessionState(sessionId, projectPath, sseConfig.queueSize).also { created ->
            sessions[sessionId] = created
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
        return sessions.remove(sessionId)?.also { it.close() }
    }

    fun sessionMap(): ConcurrentHashMap<String, SseSessionState> = sessions

    fun clearAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
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
        return removed
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
