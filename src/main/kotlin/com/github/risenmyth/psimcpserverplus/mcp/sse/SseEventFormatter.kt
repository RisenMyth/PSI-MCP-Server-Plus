package com.github.risenmyth.psimcpserverplus.mcp.sse

object SseEventFormatter {
    fun escapeJsonNewlines(json: String): String {
        return json.replace("\n", "\\n")
    }

    fun formatDataLines(data: String): List<String> {
        return data.split("\n")
    }
}
