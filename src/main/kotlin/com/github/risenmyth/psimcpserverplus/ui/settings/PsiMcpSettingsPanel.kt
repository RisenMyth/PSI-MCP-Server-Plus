package com.github.risenmyth.psimcpserverplus.ui.settings

import com.github.risenmyth.psimcpserverplus.settings.PsiMcpBindConfig
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class PsiMcpSettingsPanel {
    private val addressComboBox = JComboBox(PsiMcpBindConfig.ALLOWED_LISTEN_ADDRESSES.toTypedArray())
    private val portSpinner = JSpinner(SpinnerNumberModel(PsiMcpBindConfig.DEFAULT_PORT, 1, 65535, 1))

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Listen address:", addressComboBox)
        .addLabeledComponent("Port:", portSpinner)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    fun component(): JComponent = panel

    fun selectedConfig(): PsiMcpBindConfig {
        return PsiMcpBindConfig(
            listenAddress = PsiMcpBindConfig.sanitizeAddress(addressComboBox.selectedItem as? String),
            port = (portSpinner.value as Number).toInt(),
        )
    }

    fun reset(config: PsiMcpBindConfig) {
        addressComboBox.selectedItem = config.listenAddress
        portSpinner.value = config.port
    }
}
