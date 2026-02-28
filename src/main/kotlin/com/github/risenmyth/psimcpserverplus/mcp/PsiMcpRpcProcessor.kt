package com.github.risenmyth.psimcpserverplus.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PsiMcpRpcProcessor(
    private val router: PsiMcpProjectRouter,
    private val sessionManager: PsiMcpSessionManager,
    private val toolRegistry: PsiMcpToolRegistry,
    private val toolExecutor: PsiMcpToolExecutor,
) {
    fun handleRpc(request: JsonObject, sessionHeader: String?, projectPathHeader: String?): JsonElement? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return id?.let { rpcError(it, ERROR_INVALID_REQUEST, "Invalid request") }

        return when (method) {
            "initialize" -> handleInitialize(id, projectPathHeader)
            "notifications/initialized" -> handleInitializedNotification(id, sessionHeader, projectPathHeader)
            "tools/list" -> handleToolsList(id, sessionHeader, projectPathHeader)
            "tools/call" -> handleToolsCall(id, request, sessionHeader, projectPathHeader)
            else -> id?.let { rpcError(it, ERROR_METHOD_NOT_FOUND, "Method not found: $method") }
        }
    }

    private fun handleInitialize(id: JsonElement?, projectPathHeader: String?): JsonElement? {
        if (id == null) {
            return null
        }
        return when (val resolved = router.resolveProject(projectPathHeader)) {
            is ProjectRoutingResult.Error -> rpcError(id, ERROR_PROJECT_NOT_FOUND, resolved.message)
            is ProjectRoutingResult.Resolved -> {
                val session = sessionManager.createSession(resolved.projectPath)
                rpcResult(id, initializeResult(session.id))
            }
        }
    }

    private fun handleInitializedNotification(
        id: JsonElement?,
        sessionHeader: String?,
        projectPathHeader: String?
    ): JsonElement? {
        val session = sessionManager.getSession(sessionHeader)
            ?: return id?.let { rpcError(it, ERROR_SESSION_NOT_FOUND, "Session not found") }
        val mismatchError = sessionManager.validateProjectPathHeader(projectPathHeader, session)
        if (mismatchError != null) {
            return id?.let { rpcError(it, ERROR_PROJECT_MISMATCH, mismatchError) }
        }
        session.isInitialized = true
        session.updateActivity()
        return null
    }

    private fun handleToolsList(id: JsonElement?, sessionHeader: String?, projectPathHeader: String?): JsonElement? {
        if (id == null) {
            return null
        }
        val session = sessionManager.getSession(sessionHeader)
            ?: return rpcError(id, ERROR_SESSION_NOT_FOUND, "Session not found")
        val mismatchError = sessionManager.validateProjectPathHeader(projectPathHeader, session)
        if (mismatchError != null) {
            return rpcError(id, ERROR_PROJECT_MISMATCH, mismatchError)
        }
        if (!session.isInitialized) {
            return rpcError(id, ERROR_SESSION_NOT_INITIALIZED, "Session not initialized")
        }
        session.updateActivity()
        return rpcResult(id, toolsListResult())
    }

    private fun handleToolsCall(
        id: JsonElement?,
        request: JsonObject,
        sessionHeader: String?,
        projectPathHeader: String?,
    ): JsonElement? {
        if (id == null) {
            return null
        }

        val session = sessionManager.getSession(sessionHeader)
            ?: return rpcError(id, ERROR_SESSION_NOT_FOUND, "Session not found")
        val mismatchError = sessionManager.validateProjectPathHeader(projectPathHeader, session)
        if (mismatchError != null) {
            return rpcError(id, ERROR_PROJECT_MISMATCH, mismatchError)
        }
        if (!session.isInitialized) {
            return rpcError(id, ERROR_SESSION_NOT_INITIALIZED, "Session not initialized")
        }

        val project = router.findProjectByPath(session.projectPath)
            ?: return rpcError(id, ERROR_PROJECT_NOT_FOUND, "No project registered")
        val params = request["params"]?.jsonObject
            ?: return rpcError(id, ERROR_INVALID_PARAMS, "Invalid params")
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return rpcError(id, ERROR_INVALID_PARAMS, "Tool name missing")
        val arguments = params["arguments"]?.jsonObject ?: buildJsonObject { }

        val toolResult = toolExecutor.execute(project, name, arguments)
        session.updateActivity()
        return rpcResult(id, toolResult)
    }

    private fun toolsListResult(): JsonObject {
        val tools = toolRegistry.listTools()
        return buildJsonObject {
            put("tools", buildJsonArray {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(tool.id))
                        put("title", JsonPrimitive(tool.title))
                        put("description", JsonPrimitive(tool.description))
                        put("inputSchema", tool.inputSchema)
                    })
                }
            })
        }
    }

    private fun initializeResult(sessionId: String): JsonObject {
        return buildJsonObject {
            put("protocolVersion", JsonPrimitive("2025-11-05"))
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { })
            })
            put("serverInfo", buildJsonObject {
                put("name", JsonPrimitive("psi-mcp-server-plus"))
                put("version", JsonPrimitive("1.0.1"))
            })
            put("_sessionId", JsonPrimitive(sessionId))
        }
    }

    private fun rpcResult(id: JsonElement, result: JsonObject): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("result", result)
        }
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("error", buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            })
        }
    }

    companion object {
        const val ERROR_PARSE_ERROR = -32700
        const val ERROR_INVALID_REQUEST = -32600
        const val ERROR_METHOD_NOT_FOUND = -32601
        const val ERROR_INVALID_PARAMS = -32602
        const val ERROR_INTERNAL_ERROR = -32603
        const val ERROR_SESSION_NOT_FOUND = -32001
        const val ERROR_SESSION_NOT_INITIALIZED = -32002
        const val ERROR_PROJECT_NOT_FOUND = -32003
        const val ERROR_PROJECT_MISMATCH = -32004
    }
}
