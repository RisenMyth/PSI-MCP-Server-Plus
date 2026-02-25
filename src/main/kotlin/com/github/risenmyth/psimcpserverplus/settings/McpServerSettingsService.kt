package com.github.risenmyth.psimcpserverplus.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "PsiMcpServerPlusSettings",
    storages = [Storage("psi-mcp-server-plus.xml")],
)
class McpServerSettingsService : PersistentStateComponent<McpServerSettingsService.State> {

    data class State(
        var listenAddress: String = DEFAULT_LISTEN_ADDRESS,
        var port: Int = DEFAULT_PORT,
    )

    private var state = State()

    override fun getState(): State {
        return State(
            listenAddress = sanitizeAddress(state.listenAddress),
            port = sanitizePort(state.port),
        )
    }

    override fun loadState(state: State) {
        this.state = State(
            listenAddress = sanitizeAddress(state.listenAddress),
            port = sanitizePort(state.port),
        )
    }

    fun getBindConfig(): McpServerBindConfig {
        return McpServerBindConfig(
            listenAddress = sanitizeAddress(state.listenAddress),
            port = sanitizePort(state.port),
        )
    }

    fun updateBindConfig(config: McpServerBindConfig) {
        state.listenAddress = sanitizeAddress(config.listenAddress)
        state.port = sanitizePort(config.port)
    }

    companion object {
        const val DEFAULT_LISTEN_ADDRESS = "127.0.0.1"
        const val DEFAULT_PORT = 8765

        private val ALLOWED_LISTEN_ADDRESSES = setOf("127.0.0.1", "0.0.0.0")

        fun getInstance(): McpServerSettingsService = service()

        fun sanitizeAddress(value: String?): String {
            if (value == null) {
                return DEFAULT_LISTEN_ADDRESS
            }
            return if (ALLOWED_LISTEN_ADDRESSES.contains(value)) value else DEFAULT_LISTEN_ADDRESS
        }

        fun sanitizePort(value: Int?): Int {
            if (value == null) {
                return DEFAULT_PORT
            }
            return if (value in 1..65535) value else DEFAULT_PORT
        }
    }
}
