# PSI-MCP-Server-Plus

![Build](https://github.com/RisenMyth/PSI-MCP-Server-Plus/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Overview
PSI-MCP-Server-Plus is an IntelliJ Platform plugin that exposes a local MCP HTTP server and SSE endpoint for IDE-aware tooling.

Core capabilities:
- MCP JSON-RPC endpoint: `/mcp`
- SSE stream endpoint: `/mcp/sse`
- Multi-project routing via `PROJECT_PATH`
- Session-to-project binding and mismatch protection
- Runtime bind config (listen address and port) via IDE Settings page

<!-- Plugin description -->
PSI-MCP-Server-Plus provides a local MCP server inside IntelliJ-based IDEs. 
It supports MCP initialize/tool calls over HTTP and real-time events via SSE, with PROJECT_PATH-based routing for multi-project sessions.

Settings are available under **Tools | PSI MCP Server Plus** to configure listen address (`127.0.0.1` / `0.0.0.0`) and port.
<!-- Plugin description end -->

## Package Structure
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/startup`
  - Startup entrypoint (`MyProjectActivity`).
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/services`
  - Project/app services and routing (`PsiMcpProjectService`, `PsiMcpRouterService`).
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/mcp`
  - MCP contracts, transport server, tool registry and execution.
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/mcp/sse`
  - SSE event/session/config helpers.
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/settings`
  - Persistent settings model/service (`PsiMcpBindConfig`, `PsiMcpSettingsService`).
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/settings`
  - Settings UI (`PsiMcpSettingsConfigurable`, `PsiMcpSettingsPanel`).
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/toolwindow`
  - Reserved for future ToolWindow implementation.
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/dialog`
  - Reserved for future dialog UI.
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/actions`
  - Reserved for IDE actions.

## Configuration
Settings page: `Tools | PSI MCP Server Plus`
- Listen address: `127.0.0.1` or `0.0.0.0`
- Port: `1..65535`

Changes to address/port trigger MCP server restart when the server is already running.

## Development
Run key checks from project root:

```bash
./gradlew build
./gradlew test
```

For focused settings regression tests:

```bash
./gradlew test --tests com.github.risenmyth.psimcpserverplus.MyPluginTest.testSettingsConfigurableLifecycleIsModifiedApplyAndReset
./gradlew test --tests com.github.risenmyth.psimcpserverplus.MyPluginTest.testSettingsApplyTriggersServerReloadOnPortChange
./gradlew test --tests com.github.risenmyth.psimcpserverplus.MyPluginTest.testSettingsServiceResolutionAndPersistenceAfterRename
```

## Installation
- Use IntelliJ/IDEA plugin marketplace UI, search `PSI-MCP-Server-Plus`.
- Or install downloaded ZIP from disk in `Settings/Preferences | Plugins`.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
