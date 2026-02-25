package com.github.risenmyth.psimcpserverplus.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

@Service(Service.Level.PROJECT)
class MyProjectService(private val project: Project) {
    private val routerService = McpProjectRouterService.getInstance()

    fun ensureStarted() {
        routerService.registerProject(project)
        routerService.ensureServerStarted()
    }

    fun serverPort(): Int = routerService.serverPort()

    fun dispatchForTest(requestJson: String, sessionId: String?, projectPath: String? = null): String? {
        return routerService.dispatchForTest(requestJson, sessionId, projectPath)
    }

    init {
        Disposer.register(project) {
            routerService.unregisterProject(project)
        }
    }
}
