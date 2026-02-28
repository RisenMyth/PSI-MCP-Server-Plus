package com.github.risenmyth.psimcpserverplus.mcp

import com.github.risenmyth.psimcpserverplus.mcp.sse.SseConfig
import com.github.risenmyth.psimcpserverplus.mcp.sse.PsiMcpSseHttpHandler
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
    private val sseConfig = SseConfig()
    private val sessionManager = PsiMcpSessionManager(router, sseConfig)
    private val rpcProcessor = PsiMcpRpcProcessor(
        router = router,
        sessionManager = sessionManager,
        toolRegistry = toolRegistry,
        toolExecutor = toolExecutor,
    )
    private val sseHandler = createSseHandler()

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
        created.createContext("/mcp/sse", sseHandler)
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
        sessionManager.clearAll()
    }

    private fun cleanupExpiredSessions() {
        val timeoutMs = sseConfig.sessionTimeoutMs
        val removed = sessionManager.cleanupExpiredSessions(timeoutMs)
        if (removed > 0) {
            thisLogger().debug("Cleaned up $removed expired sessions")
        }
    }

    override fun host(): String = boundHost

    override fun port(): Int = boundPort

    override fun isRunning(): Boolean = server != null

    fun dispatchForTest(requestJson: String, sessionId: String?, projectPath: String? = null): String? {
        val parsed = json.parseToJsonElement(requestJson).jsonObject
        val response = rpcProcessor.handleRpc(parsed, sessionId, projectPath)
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
                writeJsonError(exchange, 500, PsiMcpRpcProcessor.ERROR_INTERNAL_ERROR, "Internal error")
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
                writeJsonError(exchange, HTTP_BAD_REQUEST, PsiMcpRpcProcessor.ERROR_PARSE_ERROR, "Parse error")
                return
            }

            val sessionHeader = exchange.requestHeaders.firstValue("MCP-Session-Id")
            val projectPathHeader = exchange.requestHeaders.firstValue("PROJECT_PATH")
            val response = rpcProcessor.handleRpc(jsonObject, sessionHeader, projectPathHeader)
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
            // Keep /mcp GET for backward compatibility, but route to the unified SSE implementation.
            sseHandler.handle(exchange)
        }

        private fun handleDelete(exchange: HttpExchange) {
            val sessionId = exchange.requestHeaders.firstValue("MCP-Session-Id")
            if (sessionId.isNullOrBlank()) {
                writeStatus(exchange, HTTP_BAD_REQUEST)
                return
            }
            val removed = sessionManager.removeSession(sessionId)
            if (removed != null) {
                thisLogger().debug("Session deleted: $sessionId")
            }
            writeStatus(exchange, HTTP_ACCEPTED)
        }
    }

    private fun createSseHandler(): HttpHandler {
        return PsiMcpSseHttpHandler(
            sessions = sessionManager.sessionMap(),
            sseConfig = sseConfig,
            validateProjectPathHeader = sessionManager::validateProjectPathHeader,
            isOriginAllowed = ::isOriginAllowed,
            writeStatus = ::writeStatus,
            writeJsonError = ::writeJsonError,
            isServerRunning = { server != null },
            httpBadRequest = HTTP_BAD_REQUEST,
            httpNotFound = HTTP_NOT_FOUND,
            httpConflict = HTTP_CONFLICT,
            httpMethodNotAllowed = HTTP_METHOD_NOT_ALLOWED,
            errorInvalidRequest = PsiMcpRpcProcessor.ERROR_INVALID_REQUEST,
            errorInternal = PsiMcpRpcProcessor.ERROR_INTERNAL_ERROR,
            errorSessionNotFound = PsiMcpRpcProcessor.ERROR_SESSION_NOT_FOUND,
            errorProjectMismatch = PsiMcpRpcProcessor.ERROR_PROJECT_MISMATCH,
        )
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
