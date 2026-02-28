package com.github.risenmyth.psimcpserverplus.services

import com.github.risenmyth.psimcpserverplus.mcp.ProjectRoutingResult
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpHttpServer
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpProjectRouter
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpBindConfig
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpSettingsService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class PsiMcpRouterService : PsiMcpProjectRouter {
    private val projectRegistry = ConcurrentHashMap<String, Project>()
    private val httpServer = PsiMcpHttpServer(
        router = this,
        resolveBindConfig = { PsiMcpSettingsService.getInstance().getBindConfig() },
    )

    fun registerProject(project: Project) {
        val key = normalizedProjectPathFor(project) ?: return
        projectRegistry[key] = project
        thisLogger().debug("Project registered for MCP routing: path=$key, total=${projectRegistry.size}")
    }

    fun unregisterProject(project: Project) {
        val key = normalizedProjectPathFor(project) ?: return
        projectRegistry.remove(key, project)
        thisLogger().debug("Project unregistered from MCP routing: path=$key, total=${projectRegistry.size}")
    }

    fun ensureServerStarted() {
        val wasRunning = httpServer.isRunning()
        httpServer.start()
        if (!wasRunning && httpServer.isRunning()) {
            thisLogger().info("MCP router ensured HTTP server on ${httpServer.host()}:${httpServer.port()}")
        }
    }

    fun serverHost(): String = httpServer.host()

    fun serverPort(): Int = httpServer.port()

    fun applyServerConfigIfChanged(oldConfig: PsiMcpBindConfig, newConfig: PsiMcpBindConfig) {
        if (oldConfig == newConfig) {
            return
        }
        thisLogger().info(
            "MCP bind config changed, old=${oldConfig.listenAddress}:${oldConfig.port}, new=${newConfig.listenAddress}:${newConfig.port}",
        )
        if (httpServer.isRunning()) {
            httpServer.stop()
            httpServer.start()
            thisLogger().info("MCP HTTP server reloaded on ${httpServer.host()}:${httpServer.port()}")
        }
    }

    fun dispatchForTest(requestJson: String, sessionId: String?, projectPath: String? = null): String? {
        return httpServer.dispatchForTest(requestJson, sessionId, projectPath)
    }

    override fun resolveProject(projectPathHeader: String?): ProjectRoutingResult {
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

    override fun normalizeProjectPath(path: String): String? {
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

    override fun findProjectByPath(normalizedPath: String): Project? = projectRegistry[normalizedPath]

    private fun normalizedProjectPathFor(project: Project): String? {
        val rawPath = project.basePath
            ?.takeIf { it.isNotBlank() }
            ?: project.projectFilePath?.takeIf { it.isNotBlank() }
        return rawPath?.let(::normalizeProjectPath)
    }

    internal fun registeredProjectCount(): Int = projectRegistry.size

    internal fun registeredProjectPaths(): Set<String> = projectRegistry.keys.toSet()

    companion object {
        fun getInstance(): PsiMcpRouterService = service()

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
