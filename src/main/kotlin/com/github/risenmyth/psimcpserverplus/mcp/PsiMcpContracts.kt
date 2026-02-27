package com.github.risenmyth.psimcpserverplus.mcp

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

interface PsiMcpTransportServer {
    fun start()
    fun stop()
    fun host(): String
    fun port(): Int
    fun isRunning(): Boolean
}

interface PsiMcpProjectRouter {
    fun resolveProject(projectPathHeader: String?): ProjectRoutingResult
    fun normalizeProjectPath(path: String): String?
    fun findProjectByPath(normalizedPath: String): Project?
}

fun interface PsiMcpToolHandler {
    fun invoke(project: Project, arguments: JsonObject): JsonObject
}

interface PsiMcpToolRegistry {
    fun listTools(): List<PsiMcpToolRegistration>
    fun findTool(toolId: String): PsiMcpToolRegistration?
    fun registerTool(tool: PsiMcpToolRegistration)
}

interface PsiMcpToolExecutor {
    fun execute(project: Project, toolId: String, arguments: JsonObject): JsonObject
}

data class PsiMcpToolRegistration(
    val id: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: PsiMcpToolHandler,
) {
    init {
        require(id.isNotBlank()) { "Tool id must not be blank" }
        require(title.isNotBlank()) { "Tool title must not be blank" }
        require(description.isNotBlank()) { "Tool description must not be blank" }

        val schemaType = inputSchema["type"]?.toString()?.trim('"')
        require(schemaType == "object") { "Tool inputSchema.type must be object" }
    }
}

sealed interface ProjectRoutingResult {
    data class Resolved(val project: Project, val projectPath: String) : ProjectRoutingResult
    data class Error(val message: String) : ProjectRoutingResult

    companion object {
        fun resolved(project: Project, projectPath: String): ProjectRoutingResult = Resolved(project, projectPath)
        fun error(message: String): ProjectRoutingResult = Error(message)
    }
}
