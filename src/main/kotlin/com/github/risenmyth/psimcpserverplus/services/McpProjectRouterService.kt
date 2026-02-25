package com.github.risenmyth.psimcpserverplus.services

import com.github.risenmyth.psimcpserverplus.mcp.ProjectRoutingResult
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpHttpServer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class McpProjectRouterService {
    private val projectRegistry = ConcurrentHashMap<String, Project>()
    private val httpServer = PsiMcpHttpServer(
        resolveProject = ::resolveProject,
        normalizeProjectPath = ::normalizeProjectPath,
        findProjectByPath = { normalizedPath -> projectRegistry[normalizedPath] },
    )

    fun registerProject(project: Project) {
        val key = normalizedProjectPathFor(project) ?: return
        projectRegistry[key] = project
    }

    fun unregisterProject(project: Project) {
        val key = normalizedProjectPathFor(project) ?: return
        projectRegistry.remove(key, project)
    }

    fun ensureServerStarted() {
        httpServer.start()
    }

    fun serverPort(): Int = httpServer.port()

    fun dispatchForTest(requestJson: String, sessionId: String?, projectPath: String? = null): String? {
        return httpServer.dispatchForTest(requestJson, sessionId, projectPath)
    }

    fun resolveProject(projectPathHeader: String?): ProjectRoutingResult {
        val registeredPaths = projectRegistry.keys.toSet()
        return when (val pathResolution = resolveProjectPath(projectPathHeader, registeredPaths, ::normalizeProjectPath)) {
            is ProjectPathResolution.Error -> ProjectRoutingResult.error(pathResolution.message)
            is ProjectPathResolution.Resolved -> {
                val project = projectRegistry[pathResolution.normalizedPath]
                if (project == null) {
                    ProjectRoutingResult.error("No project registered")
                } else {
                    ProjectRoutingResult.resolved(project, pathResolution.normalizedPath)
                }
            }
        }
    }

    fun normalizeProjectPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return null
        }
        var normalized = trimmed.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) {
            return null
        }
        if (isWindows()) {
            normalized = normalized.lowercase(Locale.ROOT)
        }
        return normalized
    }

    private fun normalizedProjectPathFor(project: Project): String? {
        val rawPath = project.basePath
            ?.takeIf { it.isNotBlank() }
            ?: project.projectFilePath?.takeIf { it.isNotBlank() }
        return rawPath?.let(::normalizeProjectPath)
    }

    internal fun registeredProjectCount(): Int = projectRegistry.size

    internal fun registeredProjectPaths(): Set<String> = projectRegistry.keys.toSet()

    companion object {
        fun getInstance(): McpProjectRouterService = service()

        internal fun resolveProjectPath(
            projectPathHeader: String?,
            registeredPaths: Set<String>,
            normalizePath: (String) -> String?,
        ): ProjectPathResolution {
            val explicitPath = projectPathHeader?.let(normalizePath)
            return if (explicitPath != null) {
                if (registeredPaths.contains(explicitPath)) {
                    ProjectPathResolution.Resolved(explicitPath)
                } else {
                    ProjectPathResolution.Error("Unknown PROJECT_PATH")
                }
            } else {
                when (registeredPaths.size) {
                    0 -> ProjectPathResolution.Error("No project registered")
                    1 -> ProjectPathResolution.Resolved(registeredPaths.first())
                    else -> ProjectPathResolution.Error("PROJECT_PATH header is required when multiple projects are open")
                }
            }
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
        }
    }
}

internal sealed interface ProjectPathResolution {
    data class Resolved(val normalizedPath: String) : ProjectPathResolution
    data class Error(val message: String) : ProjectPathResolution
}
