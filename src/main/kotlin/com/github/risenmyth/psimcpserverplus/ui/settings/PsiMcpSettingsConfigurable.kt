package com.github.risenmyth.psimcpserverplus.ui.settings

import com.github.risenmyth.psimcpserverplus.services.PsiMcpRouterService
import com.github.risenmyth.psimcpserverplus.settings.PsiMcpSettingsService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import javax.swing.JComponent

class PsiMcpSettingsConfigurable : Configurable {
    private var settingsPanel: PsiMcpSettingsPanel? = null

    override fun getDisplayName(): String = "PSI MCP Server Plus"

    override fun createComponent(): JComponent {
        if (settingsPanel == null) {
            settingsPanel = PsiMcpSettingsPanel()
        }
        reset()
        return settingsPanel!!.component()
    }

    override fun isModified(): Boolean {
        val panel = settingsPanel ?: return false
        val config = PsiMcpSettingsService.getInstance().getBindConfig()
        val selected = panel.selectedConfig()
        return selected.listenAddress != config.listenAddress || selected.port != config.port
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val panel = settingsPanel ?: return
        val selected = panel.selectedConfig()
        if (selected.port !in 1..65535) {
            throw ConfigurationException("Port must be in range 1..65535")
        }

        val settingsService = PsiMcpSettingsService.getInstance()
        val oldConfig = settingsService.getBindConfig()
        settingsService.updateBindConfig(selected)
        val sanitizedNewConfig = settingsService.getBindConfig()
        PsiMcpRouterService.getInstance().applyServerConfigIfChanged(oldConfig, sanitizedNewConfig)
    }

    override fun reset() {
        val panel = settingsPanel ?: return
        panel.reset(PsiMcpSettingsService.getInstance().getBindConfig())
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
