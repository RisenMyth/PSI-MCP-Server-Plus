# CLAUDE.md

This file defines project-specific guidance for Claude Code in this repository.

## Mandatory ij_mcp workflow

- Before making code changes, use `ij_mcp` to understand context:
  - `list_directory_tree` for project structure
  - `get_file_text_by_path` for file contents
- Prefer `ij_mcp` project-aware tools for search/read/edit/check operations in this repo.
- After finishing code writing, use `ij_mcp` to validate changes:
  - Run `get_file_problems` on modified files
  - Run `build_project` when changes may affect compilation, wiring, or project structure

## Runtime flow

1. `MyProjectActivity` runs on project open.
2. It calls `PsiMcpProjectService.ensureStarted()`.
3. `PsiMcpProjectService` registers project with `PsiMcpRouterService`.
4. `PsiMcpHttpServer` serves `/mcp` and handles initialize/tools calls.

## Directory structure

```text
.
├── src/
│   ├── main/
│   │   ├── kotlin/com/github/risenmyth/psimcpserverplus/
│   │   │   ├── mcp/                  # MCP HTTP server implementation (`PsiMcpHttpServer`)
│   │   │   ├── services/             # Project lifecycle + router registration services
│   │   │   ├── settings/             # MCP server settings and configurable UI
│   │   │   ├── startup/              # Startup activity (`MyProjectActivity`)
│   │   │   └── toolWindow/           # Tool window related components
│   │   └── resources/
│   │       ├── META-INF/plugin.xml   # IntelliJ plugin descriptor
│   │       └── messages/             # i18n/message resources
│   └── test/
│       ├── kotlin/.../MyPluginTest.kt
│       └── testData/rename/          # Test fixtures
├── gradle/                           # Version catalog and wrapper config
├── .github/workflows/                # CI and release pipelines
├── openspec/                         # OpenSpec change/spec artifacts
└── build.gradle.kts                  # Main Gradle build script
```

- Runtime path mainly flows through `startup` -> `services` -> `mcp`.
- Server bind/config persistence and UI are centralized in `settings`.
- Tests and fixtures are under `src/test/kotlin` and `src/test/testData`.

## Project constraints

- Keep these markers in `README.md` (required by `patchPluginXml`):
  - `<!-- Plugin description -->`
  - `<!-- Plugin description end -->`
- JVM toolchain is 21.
- IntelliJ platform version and compatibility are configured via `gradle.properties`.
