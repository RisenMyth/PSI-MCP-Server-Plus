package com.github.risenmyth.psimcpserverplus.settings

data class PsiMcpBindConfig(
    val listenAddress: String,
    val port: Int,
) {
    companion object {
        const val DEFAULT_LISTEN_ADDRESS = "127.0.0.1"
        const val BIND_ALL_ADDRESSES = "0.0.0.0"
        const val DEFAULT_PORT = 8765

        val ALLOWED_LISTEN_ADDRESSES = setOf(DEFAULT_LISTEN_ADDRESS, BIND_ALL_ADDRESSES)

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
