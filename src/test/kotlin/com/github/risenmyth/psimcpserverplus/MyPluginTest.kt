package com.github.risenmyth.psimcpserverplus

import com.github.risenmyth.psimcpserverplus.services.McpProjectRouterService
import com.github.risenmyth.psimcpserverplus.services.MyProjectService
import com.github.risenmyth.psimcpserverplus.services.ProjectPathResolution
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val projectService = project.service<MyProjectService>()
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
        val projectService = project.service<MyProjectService>()
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
        val projectService = project.service<MyProjectService>()
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
        val projectService = project.service<MyProjectService>()
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
        val projectService = project.service<MyProjectService>()
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
        val projectService = project.service<MyProjectService>()
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
        val resolution = McpProjectRouterService.resolveProjectPath(
            projectPathHeader = null,
            registeredPaths = setOf("/p1", "/p2"),
            normalizePath = { it },
        )

        assertTrue(resolution is ProjectPathResolution.Error)
        val message = (resolution as ProjectPathResolution.Error).message
        assertEquals("PROJECT_PATH header is required when multiple projects are open", message)
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
}
