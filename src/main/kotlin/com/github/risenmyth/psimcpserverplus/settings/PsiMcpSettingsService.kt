package com.github.risenmyth.psimcpserverplus.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "PsiMcpSettingsState",
    storages = [Storage("psi-mcp-server-plus.xml")],
)
class PsiMcpSettingsService : PersistentStateComponent<PsiMcpSettingsService.State> {

    data class State(
        var listenAddress: String = PsiMcpBindConfig.DEFAULT_LISTEN_ADDRESS,
        var port: Int = PsiMcpBindConfig.DEFAULT_PORT,
    )

    private var state = State()

    override fun getState(): State {
        return State(
            listenAddress = PsiMcpBindConfig.sanitizeAddress(state.listenAddress),
            port = PsiMcpBindConfig.sanitizePort(state.port),
        )
    }

    override fun loadState(state: State) {
        this.state = State(
            listenAddress = PsiMcpBindConfig.sanitizeAddress(state.listenAddress),
            port = PsiMcpBindConfig.sanitizePort(state.port),
        )
    }

    fun getBindConfig(): PsiMcpBindConfig {
        return PsiMcpBindConfig(
            listenAddress = PsiMcpBindConfig.sanitizeAddress(state.listenAddress),
            port = PsiMcpBindConfig.sanitizePort(state.port),
        )
    }

    fun updateBindConfig(config: PsiMcpBindConfig) {
        state.listenAddress = PsiMcpBindConfig.sanitizeAddress(config.listenAddress)
        state.port = PsiMcpBindConfig.sanitizePort(config.port)
    }

    companion object {
        fun getInstance(): PsiMcpSettingsService = service()
    }
}
