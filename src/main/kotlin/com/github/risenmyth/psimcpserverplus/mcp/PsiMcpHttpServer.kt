package com.github.risenmyth.psimcpserverplus.mcp

import com.github.risenmyth.psimcpserverplus.mcp.sse.SseConfig
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseConstants
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseEventFormatter
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseEventWriter
import com.github.risenmyth.psimcpserverplus.mcp.sse.SseSessionState
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpBindConfig
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PsiMcpHttpServer(
    private val router: PsiMcpProjectRouter,
    private val resolveBindConfig: () -> PsiMcpBindConfig,
    private val toolRegistry: PsiMcpToolRegistry = PsiMcpInMemoryToolRegistry(),
    private val toolExecutor: PsiMcpToolExecutor = PsiMcpDefaultToolExecutor(toolRegistry),
) : PsiMcpTransportServer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val sessions = ConcurrentHashMap<String, SseSessionState>()
    private val sseConfig = SseConfig()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var boundHost: String = ""

    @Volatile
    private var boundPort: Int = -1

    @Volatile
    private var heartbeatScheduler: ScheduledExecutorService? = null

    init {
        registerBuiltInTools()
    }

    companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415
        const val HTTP_ACCEPTED = 202

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


    override fun start() {
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
        created.createContext("/mcp/sse", SseHttpHandler())
        created.executor = Executors.newCachedThreadPool()
        created.start()

        boundHost = created.address.hostString
        boundPort = created.address.port
        server = created

        // Start heartbeat scheduler for session cleanup
        startHeartbeatScheduler()
    }

    private fun startHeartbeatScheduler() {
        val intervalMs = sseConfig.heartbeatIntervalMs
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "MCP-SSE-Heartbeat").apply { isDaemon = true }
        }
        heartbeatScheduler?.scheduleAtFixedRate(
            this::cleanupExpiredSessions,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
        thisLogger().info("SSE heartbeat scheduler started with interval ${intervalMs / 1000}s")
    }

    override fun stop() {
        heartbeatScheduler?.shutdown()
        heartbeatScheduler = null
        server?.stop(0)
        server = null
        boundHost = ""
        boundPort = -1
        sessions.clear()
    }

    private fun cleanupExpiredSessions() {
        val timeoutMs = sseConfig.sessionTimeoutMs
        val beforeSize = sessions.size
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(timeoutMs)) {
                iterator.remove()
                thisLogger().info("Session expired and removed: ${entry.key}")
            }
        }
        val afterSize = sessions.size
        if (beforeSize > afterSize) {
            thisLogger().debug("Cleaned up ${beforeSize - afterSize} expired sessions")
        }
    }

    override fun host(): String = boundHost

    override fun port(): Int = boundPort

    override fun isRunning(): Boolean = server != null

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
                        writeStatus(exchange, HTTP_METHOD_NOT_ALLOWED)
                    }
                }
            } catch (t: Throwable) {
                thisLogger().warn("Failed to process MCP HTTP request", t)
                writeJsonError(exchange, 500, ERROR_INTERNAL_ERROR, "Internal error")
            }
        }

        private fun handlePost(exchange: HttpExchange) {
            val contentType = exchange.requestHeaders.firstValue("Content-Type") ?: ""
            if (!contentType.startsWith("application/json")) {
                writeStatus(exchange, HTTP_UNSUPPORTED_MEDIA_TYPE)
                return
            }

            val requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val jsonObject = try {
                json.parseToJsonElement(requestBody).jsonObject
            } catch (_: Throwable) {
                writeJsonError(exchange, HTTP_BAD_REQUEST, ERROR_PARSE_ERROR, "Parse error")
                return
            }

            val sessionHeader = exchange.requestHeaders.firstValue("MCP-Session-Id")
            val projectPathHeader = exchange.requestHeaders.firstValue("PROJECT_PATH")
            val response = handleRpc(jsonObject, sessionHeader, projectPathHeader)
            if (response == null) {
                writeStatus(exchange, HTTP_ACCEPTED)
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
            // Legacy GET endpoint for backward compatibility
            // New clients should use /mcp/sse
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            if (sessionId.isNullOrBlank()) {
                writeStatus(exchange, HTTP_BAD_REQUEST)
                return
            }
            val session = sessions[sessionId]
            if (session == null) {
                writeStatus(exchange, HTTP_NOT_FOUND)
                return
            }
            if (!session.attachStream()) {
                writeStatus(exchange, HTTP_CONFLICT)
                return
            }

            applySseHeaders(exchange.responseHeaders)
            exchange.sendResponseHeaders(200, 0)

            try {
                exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    val sseWriter = SseEventWriter(writer, sseConfig.retryTimeoutMs)
                    sseWriter.sendInitialHandshake()
                    sseWriter.sendEvent(SseConstants.EVENT_MESSAGE, session.nextEventId(), "{}")
                    session.updateActivity()

                    while (session.streamAttached() && server != null) {
                        val payload = session.pollEvent(sseConfig.keepAliveSeconds, TimeUnit.SECONDS)
                        if (payload == null) {
                            sseWriter.sendKeepAlive()
                            continue
                        }
                        val eventId = session.nextEventId()
                        session.recordEvent(eventId, payload)
                        sseWriter.sendEvent(
                            SseConstants.EVENT_MESSAGE,
                            eventId,
                            SseEventFormatter.escapeJsonNewlines(payload),
                        )
                        session.updateActivity()
                    }
                }
            } catch (e: java.io.IOException) {
                thisLogger().debug("Legacy SSE client disconnected: ${e.message}")
            } finally {
                session.detachStream()
            }
        }

        private fun handleDelete(exchange: HttpExchange) {
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            if (sessionId.isNullOrBlank()) {
                writeStatus(exchange, HTTP_BAD_REQUEST)
                return
            }
            val removed = sessions.remove(sessionId)
            if (removed != null) {
                thisLogger().debug("Session deleted: $sessionId")
            }
            writeStatus(exchange, HTTP_ACCEPTED)
        }
    }

    /**
     * Dedicated SSE HTTP Handler for /mcp/sse endpoint
     */
    private inner class SseHttpHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val requestId = UUID.randomUUID().toString().substring(0, 8)
            thisLogger().debug("[$requestId] SSE connection request")

            try {
                if (!isOriginAllowed(exchange.requestHeaders)) {
                    writeJsonError(exchange, 403, -32600, "Forbidden origin")
                    return
                }

                when (exchange.requestMethod.uppercase(Locale.ROOT)) {
                    "GET" -> handleSseGet(exchange, requestId)
                    else -> {
                        exchange.responseHeaders.add("Allow", "GET")
                        writeStatus(exchange, HTTP_METHOD_NOT_ALLOWED)
                    }
                }
            } catch (t: Throwable) {
                thisLogger().warn("[$requestId] Failed to process SSE request", t)
                writeJsonError(exchange, 500, ERROR_INTERNAL_ERROR, "Internal error")
            }
        }

        private fun handleSseGet(exchange: HttpExchange, requestId: String) {
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            val projectPathHeader = exchange.requestHeaders.firstValue("PROJECT_PATH")
            if (sessionId.isNullOrBlank()) {
                thisLogger().debug("[$requestId] Missing session ID header")
                writeStatus(exchange, HTTP_BAD_REQUEST)
                return
            }

            val session = sessions[sessionId]
            if (session == null) {
                thisLogger().debug("[$requestId] Session not found: $sessionId")
                writeStatus(exchange, HTTP_NOT_FOUND)
                return
            }

            val mismatchError = validateProjectPathHeader(projectPathHeader, session)
            if (mismatchError != null) {
                thisLogger().debug("[$requestId] PROJECT_PATH mismatch for session: $sessionId")
                writeStatus(exchange, HTTP_CONFLICT)
                return
            }

            // Update activity timestamp
            session.updateActivity()

            if (!session.attachStream()) {
                thisLogger().debug("[$requestId] Stream already attached for session: $sessionId")
                writeStatus(exchange, HTTP_CONFLICT)
                return
            }

            thisLogger().info("[$requestId] SSE connected for session: $sessionId")

            applySseHeaders(exchange.responseHeaders)
            exchange.sendResponseHeaders(200, 0)

            try {
                exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    val sseWriter = SseEventWriter(writer, sseConfig.retryTimeoutMs)

                    // Send initial handshake with retry timeout
                    sseWriter.sendInitialHandshake()

                    // Parse Last-Event-ID header for reconnection
                    val lastEventId = parseLastEventId(exchange.requestHeaders)

                    // Send missed events if reconnecting
                    val missedEvents = session.getEventsAfter(lastEventId)
                    for ((eventId, eventPayload) in missedEvents) {
                        val escapedEvent = SseEventFormatter.escapeJsonNewlines(eventPayload)
                        sseWriter.sendEvent(SseConstants.EVENT_MESSAGE, eventId, escapedEvent)
                        session.updateActivity()
                    }

                    // Send initial empty message if no missed events
                    if (missedEvents.isEmpty() && lastEventId == null) {
                        val eventId = session.nextEventId()
                        sseWriter.sendEvent(SseConstants.EVENT_MESSAGE, eventId, "{}")
                        session.updateActivity()
                    }

                    // Surface project mismatch as SSE error if it changes after connection setup
                    val postAttachMismatch = validateProjectPathHeader(projectPathHeader, session)
                    if (postAttachMismatch != null) {
                        sseWriter.sendError(
                            eventId = session.nextEventId(),
                            errorCode = ERROR_PROJECT_MISMATCH,
                            message = "PROJECT_PATH does not match session-bound project",
                            details = postAttachMismatch,
                        )
                        return
                    }

                    // Main event loop
                    val keepAliveTimeout = sseConfig.keepAliveSeconds
                    var lastKeepAlive = System.currentTimeMillis()

                    while (session.streamAttached() && server != null) {
                        try {
                            val payload = session.pollEvent(keepAliveTimeout, TimeUnit.SECONDS)
                            val now = System.currentTimeMillis()

                            if (payload != null) {
                                val eventId = session.nextEventId()
                                session.recordEvent(eventId, payload)
                                val escapedPayload = SseEventFormatter.escapeJsonNewlines(payload)
                                sseWriter.sendEvent(SseConstants.EVENT_MESSAGE, eventId, escapedPayload)
                                session.updateActivity()
                                lastKeepAlive = now
                            } else {
                                // Check if we need to send keep-alive
                                if (now - lastKeepAlive >= keepAliveTimeout * 1000) {
                                    sseWriter.sendKeepAlive()
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
                thisLogger().info("[$requestId] SSE disconnected for session: $sessionId")
            }
        }

        private fun parseLastEventId(headers: Headers): Long? {
            val lastEventIdHeader = headers.firstValue("Last-Event-ID") ?: return null
            return lastEventIdHeader.toLongOrNull()
        }
    }

    private fun applySseHeaders(headers: Headers) {
        headers.add("Content-Type", SseConstants.CONTENT_TYPE)
        headers.add("Cache-Control", SseConstants.CACHE_CONTROL)
        headers.add("Connection", SseConstants.CONNECTION)
        headers.add("X-Accel-Buffering", SseConstants.X_ACCEL_BUFFERING)
    }

    private fun handleRpc(request: JsonObject, sessionHeader: String?, projectPathHeader: String?): JsonElement? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return id?.let { rpcError(it, ERROR_INVALID_REQUEST, "Invalid request") }

        return when (method) {
            "initialize" -> {
                if (id == null) {
                    null
                } else {
                    when (val resolved = router.resolveProject(projectPathHeader)) {
                        is ProjectRoutingResult.Error -> rpcError(id, ERROR_PROJECT_NOT_FOUND, resolved.message)
                        is ProjectRoutingResult.Resolved -> {
                            val sessionId = UUID.randomUUID().toString()
                            sessions[sessionId] = SseSessionState(sessionId, resolved.projectPath, sseConfig.queueSize)
                            thisLogger().debug("Session created: $sessionId for project: ${resolved.projectPath}")
                            rpcResult(id, initializeResult(sessionId))
                        }
                    }
                }
            }

            "notifications/initialized" -> {
                val session = validSession(sessionHeader) ?: return id?.let { rpcError(it, ERROR_SESSION_NOT_FOUND, "Session not found") }
                val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                if (mismatchError != null) {
                    return id?.let { rpcError(it, ERROR_PROJECT_MISMATCH, mismatchError) }
                }
                session.initialized = true
                session.updateActivity()
                null
            }

            "tools/list" -> {
                if (id == null) {
                    null
                } else {
                    val session = validSession(sessionHeader) ?: return rpcError(id, ERROR_SESSION_NOT_FOUND, "Session not found")
                    val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                    if (mismatchError != null) {
                        return rpcError(id, ERROR_PROJECT_MISMATCH, mismatchError)
                    }
                    if (!session.initialized) {
                        return rpcError(id, ERROR_SESSION_NOT_INITIALIZED, "Session not initialized")
                    }
                    session.updateActivity()
                    rpcResult(id, toolsListResult())
                }
            }

            "tools/call" -> {
                if (id == null) {
                    null
                } else {
                    val session = validSession(sessionHeader) ?: return rpcError(id, ERROR_SESSION_NOT_FOUND, "Session not found")
                    val mismatchError = validateProjectPathHeader(projectPathHeader, session)
                    if (mismatchError != null) {
                        return rpcError(id, ERROR_PROJECT_MISMATCH, mismatchError)
                    }
                    if (!session.initialized) {
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
                    rpcResult(id, toolResult)
                }
            }

            else -> id?.let { rpcError(it, ERROR_METHOD_NOT_FOUND, "Method not found: $method") }
        }
    }

    private fun validateProjectPathHeader(projectPathHeader: String?, session: SseSessionState): String? {
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

    private fun registerBuiltInTools() {
        val baseSchemaProperties = buildJsonObject {
            put("file_path", buildJsonObject { put("type", JsonPrimitive("string")) })
            put("line", buildJsonObject { put("type", JsonPrimitive("integer")) })
            put("column", buildJsonObject { put("type", JsonPrimitive("integer")) })
            put("include_declaration", buildJsonObject { put("type", JsonPrimitive("boolean")) })
            put("limit", buildJsonObject { put("type", JsonPrimitive("integer")) })
        }

        toolRegistry.registerTool(
            PsiMcpToolRegistration(
                id = "find_definition",
                title = "Find Definition",
                description = "Find definition location for reference at file position",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", baseSchemaProperties)
                    put("required", JsonArray(listOf(JsonPrimitive("file_path"), JsonPrimitive("line"))))
                },
                handler = PsiMcpToolHandler(::findDefinition),
            ),
        )

        toolRegistry.registerTool(
            PsiMcpToolRegistration(
                id = "find_usages",
                title = "Find Usages",
                description = "Find usages for symbol at file position",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", baseSchemaProperties)
                    put("required", JsonArray(listOf(JsonPrimitive("file_path"), JsonPrimitive("line"))))
                },
                handler = PsiMcpToolHandler(::findUsages),
            ),
        )

        toolRegistry.registerTool(
            PsiMcpToolRegistration(
                id = "get_containing_context",
                title = "Get Containing Context",
                description = "Get containing PSI context for a file location",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", baseSchemaProperties)
                    put("required", JsonArray(listOf(JsonPrimitive("file_path"), JsonPrimitive("line"))))
                },
                handler = PsiMcpToolHandler(::containingContext),
            ),
        )
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

    private fun validSession(sessionId: String?): SseSessionState? {
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

}
