package com.github.risenmyth.psimcpserverplus.startup

import com.github.risenmyth.psimcpserverplus.services.PsiMcpProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.service<PsiMcpProjectService>()
        service.ensureStarted()
        thisLogger().info("PSI MCP HTTP Stream server started on ${service.serverHost()}:${service.serverPort()} for project '${project.name}'.")
    }
}
