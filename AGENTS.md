# AGENTS.md

## 行为约束
- 尽可能使用ij_mcp工具操作项目。
- PLANS.md为计划文档，描述工作清单，在完成每一项之后，立即更新完成标识。

## 项目定位
- 本项目是一个 IntelliJ Platform 插件（Kotlin + Gradle）。
- 主要能力是提供本地 MCP HTTP 服务，并通过 SSE（Server-Sent Events）推送事件。
- 支持按 `PROJECT_PATH` 在多项目场景下进行路由与会话隔离。

## 目录结构与职责
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/startup`
  - 启动入口。`MyProjectActivity` 在项目启动后确保 MCP 服务可用。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/services`
  - 服务编排层。
  - `PsiMcpProjectService`：项目级服务，注册/注销项目。
  - `PsiMcpRouterService`：应用级服务，维护 projectPath -> Project 映射与服务重载。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/mcp`
  - MCP 协议与传输核心。
  - `PsiMcpContracts.kt`：传输、路由、工具执行等接口定义。
  - `PsiMcpHttpServer.kt`：HTTP + JSON-RPC + SSE 主处理逻辑。
  - `PsiMcpToolRegistryImpl.kt`：工具注册与执行器实现。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/mcp/sse`
  - SSE 子模块：会话状态、事件格式化、写入器、配置和常量。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/settings`
  - 配置模型与持久化服务（无 UI 逻辑）。
  - `PsiMcpBindConfig`：绑定地址/端口配置与校验。
  - `PsiMcpSettingsService`：PersistentStateComponent 持久化。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/settings`
  - 设置页 UI 层。
  - `PsiMcpSettingsConfigurable`：Settings 生命周期桥接（`isModified/apply/reset`）。
  - `PsiMcpSettingsPanel`：纯 Swing 组件装配。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/toolwindow`
  - ToolWindow 预留包（待实现）。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/ui/dialog`
  - Dialog UI 预留包（待实现）。
- `src/main/kotlin/com/github/risenmyth/psimcpserverplus/actions`
  - Action 预留包（待实现）。
- `src/main/resources/META-INF/plugin.xml`
  - 插件扩展点声明：`postStartupActivity` 与 `applicationConfigurable`。
- `src/test/kotlin/com/github/risenmyth/psimcpserverplus/MyPluginTest.kt`
  - 核心测试集合：MCP 会话、工具调用、路径路由、并发确定性、设置页回归。

## 运行链路
1. IDE 启动后触发 `postStartupActivity`。
2. 项目级服务注册当前项目到应用级路由。
3. 应用级路由服务启动/维护 HTTP 服务器。
4. 客户端通过 `/mcp` 完成 initialize 与工具调用，通过 `/mcp/sse` 接收事件。
5. `PROJECT_PATH` 用于多项目路由，session 与 projectPath 绑定并校验一致性。

## 构建与工程化
- 构建系统：Gradle Kotlin DSL（`build.gradle.kts`，`settings.gradle.kts`）。
- 平台配置：Java 21，IntelliJ Platform `2025.3.3`，`sinceBuild=253`（见 `gradle.properties`）。
- 质量工具：Kover（覆盖率）、Qodana（静态检查）、Plugin Verifier。
- CI/CD：
  - `.github/workflows/build.yml`：构建、测试、验证、草稿发布。
  - `.github/workflows/release.yml`：发布到 JetBrains Marketplace。
  - `.github/workflows/run-ui-tests.yml`：跨平台 UI 测试。

## 当前结构关注点
- SSE 处理存在两处实现痕迹（`PsiMcpHttpServer` 内部处理 + `mcp/sse/PsiMcpSseHttpHandler.kt`），后续建议统一职责边界，降低分叉维护风险。
- 测试目前集中在单个测试类，后续可继续按模块拆分（路由、协议、SSE、工具注册、并发、设置）。
