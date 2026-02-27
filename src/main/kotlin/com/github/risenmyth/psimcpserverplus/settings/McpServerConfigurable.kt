package com.github.risenmyth.psimcpserverplus.settings

import com.github.risenmyth.psimcpserverplus.services.PsiMcpRouterService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class McpServerConfigurable : Configurable {
    private val addressComboBox = JComboBox(arrayOf("127.0.0.1", "0.0.0.0"))
    private val portSpinner = JSpinner(SpinnerNumberModel(McpServerSettingsService.DEFAULT_PORT, 1, 65535, 1))
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "PSI MCP Server Plus"

    override fun createComponent(): JComponent {
        if (panel == null) {
            panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Listen address:", addressComboBox)
                .addLabeledComponent("Port:", portSpinner)
                .addComponentFillVertically(JPanel(), 0)
                .panel
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val config = McpServerSettingsService.getInstance().getBindConfig()
        return selectedAddress() != config.listenAddress || selectedPort() != config.port
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val newConfig = McpServerBindConfig(
            listenAddress = selectedAddress(),
            port = selectedPort(),
        )
        if (newConfig.port !in 1..65535) {
            throw ConfigurationException("Port must be in range 1..65535")
        }

        val settingsService = McpServerSettingsService.getInstance()
        val oldConfig = settingsService.getBindConfig()
        settingsService.updateBindConfig(newConfig)
        val sanitizedNewConfig = settingsService.getBindConfig()
        PsiMcpRouterService.getInstance().applyServerConfigIfChanged(oldConfig, sanitizedNewConfig)
    }

    override fun reset() {
        val config = McpServerSettingsService.getInstance().getBindConfig()
        addressComboBox.selectedItem = config.listenAddress
        portSpinner.value = config.port
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun selectedAddress(): String {
        return McpServerSettingsService.sanitizeAddress(addressComboBox.selectedItem as? String)
    }

    private fun selectedPort(): Int {
        return (portSpinner.value as Number).toInt()
    }
}
