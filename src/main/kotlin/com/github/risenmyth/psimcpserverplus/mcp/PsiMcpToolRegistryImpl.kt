package com.github.risenmyth.psimcpserverplus.mcp

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap

class PsiMcpInMemoryToolRegistry : PsiMcpToolRegistry {
    private val tools = ConcurrentHashMap<String, PsiMcpToolRegistration>()

    override fun listTools(): List<PsiMcpToolRegistration> = tools.values.sortedBy { it.id }

    override fun findTool(toolId: String): PsiMcpToolRegistration? = tools[toolId]

    override fun registerTool(tool: PsiMcpToolRegistration) {
        val existing = tools[tool.id]
        if (existing == null) {
            tools[tool.id] = tool
            return
        }

        val existingContract = existing.contractFingerprint()
        val incomingContract = tool.contractFingerprint()
        require(existingContract == incomingContract) {
            "Tool contract mismatch for id '${tool.id}'. Additive expansion must not change existing tool contract."
        }
    }

    private fun PsiMcpToolRegistration.contractFingerprint(): JsonObject {
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("description", JsonPrimitive(description))
            put("inputSchema", inputSchema)
        }
    }
}

class PsiMcpDefaultToolExecutor(
    private val registry: PsiMcpToolRegistry,
) : PsiMcpToolExecutor {
    override fun execute(project: Project, toolId: String, arguments: JsonObject): JsonObject {
        val tool = registry.findTool(toolId)
            ?: return toolError("Unknown tool: $toolId")
        return tool.handler.invoke(project, arguments)
    }

    private fun toolError(message: String): JsonObject {
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(message))
                })
            })
            put("isError", JsonPrimitive(true))
        }
    }
}
