package com.github.risenmyth.psimcpserverplus

import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpHttpServer
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpInMemoryToolRegistry
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpProjectRouter
import com.github.risenmyth.psimcpserverplus.mcp.PsiMcpToolRegistration
import com.github.risenmyth.psimcpserverplus.services.PsiMcpProjectService
import com.github.risenmyth.psimcpserverplus.services.PsiMcpRouterService
import com.github.risenmyth.psimcpserverplus.services.ProjectPathResolution
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpBindConfig
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpSettingsService
import com.github.risenmyth.psimcpserverplus.ui.settings.PsiMcpSettingsConfigurable
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.PsiErrorElementUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Component
import java.awt.Container
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner

class MyPluginTest : BasePlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        assertFalse(PsiErrorElementUtil.hasErrors(project, psiFile.virtualFile))
    }

    fun testRename() {
        val psiFile = myFixture.configureByText(
            XmlFileType.INSTANCE,
            "<root><a<caret>1>x</a1></root>",
        )
        myFixture.renameElementAtCaret("a2")
        assertTrue(psiFile.text.contains("<a2>x</a2>"))
    }

    fun testMcpToolsList() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
        )
        assertNotNull(initResponse)
        val initJson = parseObject(initResponse!!)
        val sessionId = extractSessionId(initJson)
        assertNotNull(sessionId)

        projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionId,
        )

        val toolsResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """.trimIndent(),
            sessionId,
        )
        assertNotNull(toolsResponse)
        assertTrue(toolsResponse!!.contains("get_containing_context"))
    }

    fun testMcpFindDefinition() {
        val psiFile = myFixture.configureByText(
            "Sample.kt",
            """
            class Sample {
                fun target() {}
                fun use() {
                    target()
                }
            }
            """.trimIndent(),
        )

        val normalizedPath = FileUtil.toSystemIndependentName(psiFile.virtualFile.url)
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":10,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
        )
        val initJson = parseObject(initResponse!!)
        val sessionId = extractSessionId(initJson)
        assertNotNull(sessionId)

        projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionId,
        )

        val response = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"find_definition","arguments":{"file_path":"$normalizedPath","line":4,"column":9}}}
            """.trimIndent(),
            sessionId,
        )

        assertNotNull(response)
        val responseText = response!!
        assertTrue(responseText, responseText.contains("\"isError\":false"))
        assertTrue(responseText, responseText.contains("\"file_path\""))
    }

    fun testInitializeWithoutProjectPathSucceedsWhenSingleProjectRegistered() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":101,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
        )

        assertNotNull(initResponse)
        val initJson = parseObject(initResponse!!)
        assertNotNull(extractSessionId(initJson))
    }

    fun testInitializeWithValidProjectPathSucceeds() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":102,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
            projectRoutingPath(),
        )

        assertNotNull(initResponse)
        val initJson = parseObject(initResponse!!)
        assertNotNull(extractSessionId(initJson))
    }

    fun testInitializeWithUnknownProjectPathReturnsError() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":103,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
            "Z:/not-registered/project",
        )

        assertNotNull(initResponse)
        val initJson = parseObject(initResponse!!)
        val errorMessage = initJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        assertEquals("Unknown PROJECT_PATH", errorMessage)
    }

    fun testSessionProjectPathMismatchReturnsError() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":104,"method":"initialize","params":{}}
            """.trimIndent(),
            null,
            projectRoutingPath(),
        )
        val initJson = parseObject(initResponse!!)
        val sessionId = extractSessionId(initJson)
        assertNotNull(sessionId)

        projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionId,
            projectRoutingPath(),
        )

        val mismatchResponse = projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","id":105,"method":"tools/list"}
            """.trimIndent(),
            sessionId,
            "Z:/different/project",
        )

        assertNotNull(mismatchResponse)
        val mismatchJson = parseObject(mismatchResponse!!)
        val errorMessage = mismatchJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        assertEquals("PROJECT_PATH does not match session-bound project", errorMessage)
    }

    fun testResolveProjectPathRequiresHeaderWhenMultipleProjectsRegistered() {
        val resolution = PsiMcpRouterService.resolveProjectPath(
            projectPathHeader = null,
            registeredPaths = setOf("/p1", "/p2"),
            normalizePath = { it },
        )

        assertTrue(resolution is ProjectPathResolution.Error)
        val message = (resolution as ProjectPathResolution.Error).message
        assertEquals("PROJECT_PATH header is required when multiple projects are open", message)
    }

    fun testRegistryRejectsContractChangeForSameToolId() {
        val registry = PsiMcpInMemoryToolRegistry()

        registry.registerTool(
            PsiMcpToolRegistration(
                id = "same_id",
                title = "Title",
                description = "Desc",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject { })
                },
                handler = { _, _ -> errorResult("first") },
            ),
        )

        try {
            registry.registerTool(
                PsiMcpToolRegistration(
                    id = "same_id",
                    title = "Changed",
                    description = "Desc",
                    inputSchema = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject { })
                    },
                    handler = { _, _ -> errorResult("second") },
                ),
            )
            fail("Expected IllegalArgumentException for incompatible contract re-registration")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    fun testRegistryListIsDeterministicByToolId() {
        val registry = PsiMcpInMemoryToolRegistry()
        registry.registerTool(testTool("z_tool"))
        registry.registerTool(testTool("a_tool"))
        registry.registerTool(testTool("m_tool"))

        val ids = registry.listTools().map { it.id }
        assertEquals(listOf("a_tool", "m_tool", "z_tool"), ids)
    }

    fun testToolsListDeterministicAndRegistryDerived() {
        val projectService = project.service<PsiMcpProjectService>()
        projectService.ensureStarted()

        val initJson = parseObject(
            projectService.dispatchForTest(
                """
                {"jsonrpc":"2.0","id":201,"method":"initialize","params":{}}
                """.trimIndent(),
                null,
            )!!,
        )
        val sessionId = extractSessionId(initJson)!!

        projectService.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionId,
        )

        val response1 = parseObject(
            projectService.dispatchForTest(
                """
                {"jsonrpc":"2.0","id":202,"method":"tools/list"}
                """.trimIndent(),
                sessionId,
            )!!,
        )

        val response2 = parseObject(
            projectService.dispatchForTest(
                """
                {"jsonrpc":"2.0","id":203,"method":"tools/list"}
                """.trimIndent(),
                sessionId,
            )!!,
        )

        val tools1 = response1["result"]!!.jsonObject["tools"]!!.jsonArray
        val tools2 = response2["result"]!!.jsonObject["tools"]!!.jsonArray

        assertEquals(tools1.map { it.jsonObject["name"]!!.jsonPrimitive.content }, tools2.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("find_definition", "find_usages", "get_containing_context"), tools1.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    fun testExistingAndNewToolInvokeInSameRuntime() {
        val registry = PsiMcpInMemoryToolRegistry()
        registry.registerTool(testTool("existing_tool"))
        registry.registerTool(testTool("new_tool"))

        val existing = registry.findTool("existing_tool")?.handler?.invoke(project, buildJsonObject { })
        val added = registry.findTool("new_tool")?.handler?.invoke(project, buildJsonObject { })

        assertNotNull(existing)
        assertNotNull(added)
        assertFalse(existing!!["isError"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(added!!["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    fun testConcurrentRegistryAccessAndProjectIsolation() {
        val router = object : PsiMcpProjectRouter {
            private val known = setOf("/project-a", "/project-b")

            override fun resolveProject(projectPathHeader: String?) = when {
                projectPathHeader == null -> error("PROJECT_PATH required")
                projectPathHeader in known -> com.github.risenmyth.psimcpserverplus.mcp.ProjectRoutingResult.resolved(project, projectPathHeader)
                else -> com.github.risenmyth.psimcpserverplus.mcp.ProjectRoutingResult.error("Unknown PROJECT_PATH")
            }

            override fun normalizeProjectPath(path: String): String? = path.ifBlank { null }

            override fun findProjectByPath(normalizedPath: String): Project? = if (normalizedPath in known) project else null
        }

        val server = PsiMcpHttpServer(
            router = router,
            resolveBindConfig = { error("not needed") },
        )

        val sessionA = extractSessionId(
            parseObject(
                server.dispatchForTest(
                    """
                    {"jsonrpc":"2.0","id":301,"method":"initialize","params":{}}
                    """.trimIndent(),
                    null,
                    "/project-a",
                )!!,
            ),
        )!!

        val sessionB = extractSessionId(
            parseObject(
                server.dispatchForTest(
                    """
                    {"jsonrpc":"2.0","id":302,"method":"initialize","params":{}}
                    """.trimIndent(),
                    null,
                    "/project-b",
                )!!,
            ),
        )!!

        server.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionA,
            "/project-a",
        )
        server.dispatchForTest(
            """
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent(),
            sessionB,
            "/project-b",
        )

        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = mutableListOf<Future<Boolean>>()
            repeat(40) { idx ->
                jobs += pool.submit(Callable {
                    val responseA = parseObject(
                        server.dispatchForTest(
                            """
                            {"jsonrpc":"2.0","id":${400 + idx},"method":"tools/list"}
                            """.trimIndent(),
                            sessionA,
                            "/project-a",
                        )!!,
                    )
                    val responseB = parseObject(
                        server.dispatchForTest(
                            """
                            {"jsonrpc":"2.0","id":${500 + idx},"method":"tools/list"}
                            """.trimIndent(),
                            sessionB,
                            "/project-b",
                        )!!,
                    )
                    val mismatch = parseObject(
                        server.dispatchForTest(
                            """
                            {"jsonrpc":"2.0","id":${600 + idx},"method":"tools/list"}
                            """.trimIndent(),
                            sessionA,
                            "/project-b",
                        )!!,
                    )

                    responseA["result"] != null &&
                        responseB["result"] != null &&
                        mismatch["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull == "PROJECT_PATH does not match session-bound project"
                })
            }
            jobs.forEach { assertTrue(it.get()) }
        } finally {
            pool.shutdownNow()
        }
    }

    fun testSettingsConfigurableLifecycleIsModifiedApplyAndReset() {
        val settingsService = PsiMcpSettingsService.getInstance()
        val original = settingsService.getBindConfig()
        val initial = PsiMcpBindConfig(
            listenAddress = PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS,
            port = 22000,
        )

        try {
            settingsService.updateBindConfig(initial)
            val configurable = PsiMcpSettingsConfigurable()
            lateinit var component: JComponent
            lateinit var addressBox: JComboBox<*>
            lateinit var portSpinner: JSpinner

            runInEdtAndWait {
                component = configurable.createComponent()
                addressBox = findComponent(component)
                portSpinner = findComponent(component)
            }

            assertFalse(configurable.isModified())

            runInEdtAndWait {
                addressBox.selectedItem = PsiMcpBindConfig.BIND_ALL_ADDRESSES
                portSpinner.value = 22001
            }

            assertTrue(configurable.isModified())
            runInEdtAndWait { configurable.apply() }
            assertEquals(PsiMcpBindConfig(PsiMcpBindConfig.BIND_ALL_ADDRESSES, 22001), settingsService.getBindConfig())

            runInEdtAndWait { addressBox.selectedItem = PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS }
            assertTrue(configurable.isModified())
            runInEdtAndWait { configurable.reset() }

            val resetConfig = settingsService.getBindConfig()
            assertEquals(resetConfig.listenAddress, addressBox.selectedItem)
            assertEquals(resetConfig.port, (portSpinner.value as Number).toInt())
            assertFalse(configurable.isModified())
            runInEdtAndWait { configurable.disposeUIResources() }
        } finally {
            settingsService.updateBindConfig(original)
        }
    }

    fun testSettingsApplyTriggersServerReloadOnPortChange() {
        val settingsService = PsiMcpSettingsService.getInstance()
        val routerService = PsiMcpRouterService.getInstance()
        val original = settingsService.getBindConfig()
        val oldPort = findFreePort()
        val newPort = findFreePort(exclude = setOf(oldPort))

        try {
            settingsService.updateBindConfig(PsiMcpBindConfig(PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS, oldPort))
            routerService.ensureServerStarted()
            val runningPortBefore = routerService.serverPort()

            val configurable = PsiMcpSettingsConfigurable()
            lateinit var component: JComponent
            lateinit var addressBox: JComboBox<*>
            lateinit var portSpinner: JSpinner

            runInEdtAndWait {
                component = configurable.createComponent()
                addressBox = findComponent(component)
                portSpinner = findComponent(component)
                addressBox.selectedItem = PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS
                portSpinner.value = newPort
                configurable.apply()
                configurable.disposeUIResources()
            }

            assertEquals(newPort, settingsService.getBindConfig().port)
            assertEquals(newPort, routerService.serverPort())
            assertTrue(runningPortBefore != routerService.serverPort())
        } finally {
            val current = settingsService.getBindConfig()
            settingsService.updateBindConfig(original)
            routerService.applyServerConfigIfChanged(current, settingsService.getBindConfig())
        }
    }

    fun testSettingsServiceResolutionAndPersistenceAfterRename() {
        val settingsService = service<PsiMcpSettingsService>()
        val original = settingsService.getBindConfig()

        try {
            settingsService.loadState(
                PsiMcpSettingsService.State(
                    listenAddress = "not-allowed",
                    port = 70000,
                ),
            )
            assertEquals(
                PsiMcpBindConfig(PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS, PsiMcpBindConfig.DEFAULT_PORT),
                settingsService.getBindConfig(),
            )

            val expected = PsiMcpBindConfig(PsiMcpBindConfig.BIND_ALL_ADDRESSES, 23001)
            settingsService.updateBindConfig(expected)
            assertEquals(expected, settingsService.getBindConfig())
            assertEquals(expected.listenAddress, settingsService.getState().listenAddress)
            assertEquals(expected.port, settingsService.getState().port)
        } finally {
            settingsService.updateBindConfig(original)
        }
    }

    private fun testTool(id: String): PsiMcpToolRegistration {
        return PsiMcpToolRegistration(
            id = id,
            title = id,
            description = id,
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { })
            },
            handler = { _, _ ->
                buildJsonObject {
                    put("content", json.parseToJsonElement("[{\"type\":\"text\",\"text\":\"ok\"}]") )
                    put("isError", JsonPrimitive(false))
                }
            },
        )
    }

    private fun errorResult(message: String): JsonObject {
        return buildJsonObject {
            put("content", json.parseToJsonElement("[{\"type\":\"text\",\"text\":\"$message\"}]") )
            put("isError", JsonPrimitive(true))
        }
    }

    private fun parseObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun extractSessionId(obj: JsonObject): String? {
        return obj["result"]
            ?.jsonObject
            ?.get("_sessionId")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private fun projectRoutingPath(): String {
        return project.basePath
            ?: project.projectFilePath
            ?: error("Project path unavailable for test")
    }

    private inline fun <reified T : Component> findComponent(root: Component): T =
        findComponent(root, T::class.java)

    private fun <T : Component> findComponent(root: Component, componentClass: Class<T>): T {
        if (componentClass.isInstance(root)) {
            return componentClass.cast(root)
        }
        if (root is Container) {
            for (child in root.components) {
                try {
                    return findComponent(child, componentClass)
                } catch (_: NoSuchElementException) {
                    // Continue search in next child.
                }
            }
        }
        throw NoSuchElementException("Component ${componentClass.simpleName} not found")
    }

    private fun findFreePort(exclude: Set<Int> = emptySet()): Int {
        repeat(20) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (!exclude.contains(candidate)) {
                return candidate
            }
        }
        error("Unable to allocate free TCP port")
    }
}
