package com.github.risenmyth.psimcpserverplus.mcp

import com.github.risenmyth.psimcpserverplus.settings.McpServerBindConfig
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PsiMcpHttpServer(
    private val resolveProject: (String?) -> ProjectRoutingResult,
    private val normalizeProjectPath: (String) -> String?,
    private val findProjectByPath: (String) -> Project?,
    private val resolveBindConfig: () -> McpServerBindConfig,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val sessions = ConcurrentHashMap<String, SessionState>()
    @Volatile
    private var server: HttpServer? = null
    @Volatile
    private var boundHost: String = ""
    @Volatile
    private var boundPort: Int = -1

    fun start() {
        if (server != null) {
            return
        }

        val config = resolveBindConfig()
        val created = try {
            HttpServer.create(InetSocketAddress(config.listenAddress, config.port), 0)
        } catch (_: BindException) {
            HttpServer.create(InetSocketAddress(config.listenAddress, 0), 0)
        }

        created.createContext("/mcp", McpHttpHandler())
        created.executor = Executors.newCachedThreadPool()
        created.start()

        boundHost = created.address.hostString
        boundPort = created.address.port
        server = created
    }

    fun stop() {
        server?.stop(0)
        server = null
        boundHost = ""
        boundPort = -1
        sessions.clear()
    }

    fun host(): String = boundHost

    fun port(): Int = boundPort

    fun isRunning(): Boolean = server != null

    fun dispatchForTest(requestJson: String, sessionId: String?, projectPath: String? = null): String? {
        val parsed = json.parseToJsonElement(requestJson).jsonObject
        val response = handleRpc(parsed, sessionId, projectPath)
        return response?.let { json.encodeToString(JsonElement.serializer(), it) }
    }

    private inner class McpHttpHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                if (!isOriginAllowed(exchange.requestHeaders)) {
                    writeJsonError(exchange, 403, -32600, "Forbidden origin")
                    return
                }

                when (exchange.requestMethod.uppercase(Locale.ROOT)) {
                    "POST" -> handlePost(exchange)
                    "GET" -> handleGet(exchange)
                    "DELETE" -> handleDelete(exchange)
                    else -> {
                        exchange.responseHeaders.add("Allow", "POST, GET, DELETE")
                        writeStatus(exchange, 405)
                    }
                }
            } catch (t: Throwable) {
                thisLogger().warn("Failed to process MCP HTTP request", t)
                writeJsonError(exchange, 500, -32603, "Internal error")
            }
        }

        private fun handlePost(exchange: HttpExchange) {
            val contentType = exchange.requestHeaders.firstValue("Content-Type") ?: ""
            if (!contentType.startsWith("application/json")) {
                writeStatus(exchange, 415)
                return
            }

            val requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val jsonObject = try {
                json.parseToJsonElement(requestBody).jsonObject
            } catch (_: Throwable) {
                writeJsonError(exchange, 400, -32700, "Parse error")
                return
            }

            val sessionHeader = exchange.requestHeaders.firstValue("MCP-Session-Id")
            val projectPathHeader = exchange.requestHeaders.firstValue("PROJECT_PATH")
            val response = handleRpc(jsonObject, sessionHeader, projectPathHeader)
            if (response == null) {
                writeStatus(exchange, 202)
                return
            }

            val responseText = json.encodeToString(JsonElement.serializer(), response)
            val responseBytes = responseText.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")

            val method = jsonObject["method"]?.jsonPrimitive?.contentOrNull
            if (method == "initialize") {
                val newSessionId = response.jsonObject["result"]
                    ?.jsonObject
                    ?.get("_sessionId")
                    ?.jsonPrimitive
                    ?.contentOrNull
                if (newSessionId != null) {
                    exchange.responseHeaders.add("MCP-Session-Id", newSessionId)
                }
            }

            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        private fun handleGet(exchange: HttpExchange) {
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            if (sessionId.isNullOrBlank()) {
                writeStatus(exchange, 400)
                return
            }
            val session = sessions[sessionId]
            if (session == null) {
                writeStatus(exchange, 404)
                return
            }
            if (!session.attachStream()) {
                writeStatus(exchange, 409)
                return
            }

            val headers = exchange.responseHeaders
            headers.add("Content-Type", "text/event-stream; charset=utf-8")
            headers.add("Cache-Control", "no-cache")
            headers.add("Connection", "keep-alive")
            headers.add("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)

            exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("event: message\n")
                writer.write("id: ${session.nextEventId()}\n")
                writer.write("data: {}\n\n")
                writer.flush()

                while (session.streamAttached && server != null) {
                    val payload = session.pollEvent(15, TimeUnit.SECONDS)
                    if (payload == null) {
                        writer.write(": keep-alive\n\n")
                        writer.flush()
                        continue
                    }
                    writer.write("event: message\n")
                    writer.write("id: ${session.nextEventId()}\n")
                    writer.write("data: $payload\n\n")
                    writer.flush()
                }
            }

            session.detachStream()
        }

        private fun handleDelete(exchange: HttpExchange) {
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            if (sessionId.isNullOrBlank()) {
                writeStatus(exchange, 400)
                return
            }
            sessions.remove(sessionId)
            writeStatus(exchange, 202)
        }
    }

    private fun handleRpc(request: JsonObject, sessionHeader: String?, projectPathHeader: String?): JsonElement? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return id?.let { rpcError(it, -32600, "Invalid request") }

        return when (method) {
            "initialize" -> {
                if (id == null) {
                    null
                } else {
                    when (val resolved = resolveProject(projectPathHeader)) {
                        is ProjectRoutingResult.Error -> rpcError(id, -32003, resolved.message)
                        is ProjectRoutingResult.Resolved -> {
                            val sessionId = UUID.randomUUID().toString()
                            sessions[sessionId] = SessionState(sessionId, resolved.projectPath)
                            rpcResult(id, initializeResult(sessionId))
                        }
                    }
                }
            }

            "notifications/initialized" -> {
                val session = validSession(sessionHeader) ?: return id?.let { rpcError(it, -32001, "Session not found") }
                val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                if (mismatchError != null) {
                    return id?.let { rpcError(it, -32004, mismatchError) }
                }
                session.initialized = true
                null
            }

            "tools/list" -> {
                if (id == null) {
                    null
                } else {
                    val session = validSession(sessionHeader) ?: return rpcError(id, -32001, "Session not found")
                    val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                    if (mismatchError != null) {
                        return rpcError(id, -32004, mismatchError)
                    }
                    if (!session.initialized) {
                        return rpcError(id, -32002, "Session not initialized")
                    }
                    rpcResult(id, toolsListResult())
                }
            }

            "tools/call" -> {
                if (id == null) {
                    null
                } else {
                    val session = validSession(sessionHeader) ?: return rpcError(id, -32001, "Session not found")
                    val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                    if (mismatchError != null) {
                        return rpcError(id, -32004, mismatchError)
                    }
                    if (!session.initialized) {
                        return rpcError(id, -32002, "Session not initialized")
                    }
                    val project = findProjectByPath(session.projectPath)
                        ?: return rpcError(id, -32003, "No project registered")
                    val params = request["params"]?.jsonObject
                        ?: return rpcError(id, -32602, "Invalid params")
                    val name = params["name"]?.jsonPrimitive?.contentOrNull
                        ?: return rpcError(id, -32602, "Tool name missing")
                    val arguments = params["arguments"]?.jsonObject ?: buildJsonObject { }
                    val toolResult = handleToolCall(project, name, arguments)
                    rpcResult(id, toolResult)
                }
            }

            else -> id?.let { rpcError(it, -32601, "Method not found: $method") }
        }
    }

    private fun validateProjectPathHeader(projectPathHeader: String?, session: SessionState): String? {
        if (projectPathHeader == null) {
            return null
        }
        val normalizedHeader = normalizeProjectPath(projectPathHeader)
            ?: return "Invalid PROJECT_PATH"
        if (normalizedHeader != session.projectPath) {
            return "PROJECT_PATH does not match session-bound project"
        }
        return null
    }

    private fun handleToolCall(project: Project, name: String, arguments: JsonObject): JsonObject {
        return when (name) {
            "get_containing_context" -> containingContext(project, arguments)
            "find_definition" -> findDefinition(project, arguments)
            "find_usages" -> findUsages(project, arguments)
            else -> toolError("Unknown tool: $name")
        }
    }

    private fun containingContext(project: Project, arguments: JsonObject): JsonObject {
        val location = resolveLocation(project, arguments) ?: return toolError("Invalid file_path/line/column")
        val context = ReadAction.compute<String, RuntimeException> {
            val nearestNamed = PsiTreeUtil.getParentOfType(location.element, PsiNamedElement::class.java)
            val className = nearestNamed?.javaClass?.simpleName ?: location.file.javaClass.simpleName
            val name = nearestNamed?.name ?: location.file.name
            "context=$className name=$name file=${location.file.virtualFile.path} line=${location.line} column=${location.column}"
        }
        return toolTextWithStructured(context, buildJsonObject {
            put("context", JsonPrimitive(context))
        })
    }

    private fun findDefinition(project: Project, arguments: JsonObject): JsonObject {
        val location = resolveLocation(project, arguments) ?: return toolError("Invalid file_path/line/column")
        val definition = ReadAction.compute<LocationResult?, RuntimeException> {
            val direct = location.element.reference?.resolve()
            val parentReferenced = generateSequence(location.element) { it.parent }
                .mapNotNull { it.reference?.resolve() }
                .firstOrNull()
            val target = direct ?: parentReferenced ?: location.element.navigationElement
            toLocation(project, target)
        }

        return if (definition == null) {
            toolError("Definition not found")
        } else {
            val text = "definition file=${definition.filePath} line=${definition.line} column=${definition.column}"
            toolTextWithStructured(text, buildJsonObject {
                put("file_path", JsonPrimitive(definition.filePath))
                put("line", JsonPrimitive(definition.line))
                put("column", JsonPrimitive(definition.column))
            })
        }
    }

    private fun findUsages(project: Project, arguments: JsonObject): JsonObject {
        val location = resolveLocation(project, arguments) ?: return toolError("Invalid file_path/line/column")
        val includeDeclaration = arguments["include_declaration"]?.jsonPrimitive?.contentOrNull == "true"
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 500) ?: 100

        val usages = ReadAction.compute<List<LocationResult>, RuntimeException> {
            val target = location.element.reference?.resolve() ?: location.element
            val results = mutableListOf<LocationResult>()

            if (includeDeclaration) {
                toLocation(project, target)?.let(results::add)
            }

            val references = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
            for (reference in references) {
                if (results.size >= limit) {
                    break
                }
                toLocation(project, reference.element)?.let(results::add)
            }

            results
        }

        val summary = "usages=${usages.size}"
        return toolTextWithStructured(summary, buildJsonObject {
            put("count", JsonPrimitive(usages.size))
            put("items", JsonArray(usages.map {
                buildJsonObject {
                    put("file_path", JsonPrimitive(it.filePath))
                    put("line", JsonPrimitive(it.line))
                    put("column", JsonPrimitive(it.column))
                }
            }))
        })
    }

    private fun resolveLocation(project: Project, arguments: JsonObject): ResolvedLocation? {
        val filePath = arguments["file_path"]?.jsonPrimitive?.contentOrNull ?: return null
        val line = arguments["line"]?.jsonPrimitive?.intOrNull ?: return null
        val column = arguments["column"]?.jsonPrimitive?.intOrNull ?: 1

        val normalized = filePath.replace('\\', '/')
        return ReadAction.compute<ResolvedLocation?, RuntimeException> {
            val vFile = if (normalized.contains("://")) {
                VirtualFileManager.getInstance().findFileByUrl(normalized)
            } else {
                LocalFileSystem.getInstance().findFileByPath(normalized)
            } ?: return@compute null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@compute null
            if (line <= 0 || line > document.lineCount) {
                return@compute null
            }

            val lineIndex = line - 1
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val requestedColumn = if (column <= 0) 1 else column
            val offset = (lineStart + (requestedColumn - 1)).coerceIn(lineStart, lineEnd)

            val element = psiFile.findElementAt(offset) ?: psiFile
            ResolvedLocation(psiFile, element, line, requestedColumn)
        }
    }

    private fun toLocation(project: Project, element: PsiElement): LocationResult? {
        val file = element.containingFile ?: return null
        val vFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = element.textOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset) + 1
        val lineStart = document.getLineStartOffset(line - 1)
        val column = (offset - lineStart) + 1
        return LocationResult(vFile.path, line, column)
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

    private fun toolsListResult(): JsonObject {
        return buildJsonObject {
            put("tools", buildJsonArray {
                add(toolDef(
                    name = "get_containing_context",
                    description = "Get containing PSI context for a file location",
                    required = listOf("file_path", "line"),
                ))
                add(toolDef(
                    name = "find_definition",
                    description = "Find definition location for reference at file position",
                    required = listOf("file_path", "line"),
                ))
                add(toolDef(
                    name = "find_usages",
                    description = "Find usages for symbol at file position",
                    required = listOf("file_path", "line"),
                ))
            })
        }
    }

    private fun toolDef(name: String, description: String, required: List<String>): JsonObject {
        return buildJsonObject {
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            put("inputSchema", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("file_path", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    })
                    put("line", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                    })
                    put("column", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                    })
                    put("include_declaration", buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                    })
                    put("limit", buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                    })
                })
                put("required", JsonArray(required.map(::JsonPrimitive)))
            })
        }
    }

    private fun toolTextWithStructured(text: String, structured: JsonObject): JsonObject {
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                })
            })
            put("structuredContent", structured)
            put("isError", JsonPrimitive(false))
        }
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

    private fun writeStatus(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun writeJsonError(exchange: HttpExchange, status: Int, code: Int, message: String) {
        val body = json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonNull)
                put("error", buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("message", JsonPrimitive(message))
                })
            },
        ).toByteArray(StandardCharsets.UTF_8)

        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun validSession(sessionId: String?): SessionState? {
        if (sessionId.isNullOrBlank()) {
            return null
        }
        return sessions[sessionId]
    }

    private fun Headers.firstValue(name: String): String? {
        return this.entries.firstOrNull { (k, _) -> k.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }

    private fun isOriginAllowed(headers: Headers): Boolean {
        val origin = headers.firstValue("Origin") ?: return true
        val lower = origin.lowercase(Locale.ROOT)
        return lower.startsWith("http://localhost") ||
            lower.startsWith("https://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("https://127.0.0.1")
    }

    private data class ResolvedLocation(
        val file: PsiFile,
        val element: PsiElement,
        val line: Int,
        val column: Int,
    )

    private data class LocationResult(
        val filePath: String,
        val line: Int,
        val column: Int,
    )

    private class SessionState(@Suppress("unused") val id: String, val projectPath: String) {
        @Volatile
        var initialized: Boolean = false

        @Volatile
        var streamAttached: Boolean = false

        private val eventQueue = LinkedBlockingQueue<String>()
        private var eventCounter: Long = 0L

        fun attachStream(): Boolean {
            if (streamAttached) {
                return false
            }
            streamAttached = true
            return true
        }

        fun detachStream() {
            streamAttached = false
        }

        fun pollEvent(timeout: Long, unit: TimeUnit): String? = eventQueue.poll(timeout, unit)

        fun nextEventId(): Long {
            eventCounter += 1
            return eventCounter
        }
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
