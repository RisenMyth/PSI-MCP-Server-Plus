# UI Refactor Decisions

## Scope
- Refactor settings/UI naming and package layering only.
- Keep MCP protocol and server runtime behavior unchanged.

## Naming Baseline
- Unified prefix: `PsiMcp*`.
- Removed `McpServer*` type naming from runtime code.

## Package Rules
- `settings` package:
  - Persistent model and state service only.
  - No Swing layout or Settings page lifecycle code.
- `ui.*` packages:
  - UI implementation only.
  - Must not own persistence responsibilities.
- Reserved packages were created for future growth:
  - `ui.toolwindow`
  - `ui.dialog`
  - `actions`

## Settings Layering Decision
- `PsiMcpSettingsConfigurable` moved to `ui.settings`.
- Added `PsiMcpSettingsPanel` to isolate Swing component assembly.
- `Configurable` remains a lifecycle bridge: `isModified`, `apply`, `reset`, and router reload trigger.

## Config Model Decision
- Consolidated bind constants and sanitization rules in `PsiMcpBindConfig`.
- `PsiMcpSettingsService` now delegates address/port normalization to `PsiMcpBindConfig`.

## plugin.xml Update
- `applicationConfigurable.instance` now points to:
  - `com.github.risenmyth.psimcpserverplus.ui.settings.PsiMcpSettingsConfigurable`

## Regression Coverage Added
- Settings lifecycle test:
  - `isModified` / `apply` / `reset`
- Server reload behavior test:
  - Port change triggers router server restart path.
- Rename/persistence test:
  - Service resolution and state sanitize/persist behavior.

## Known Test Environment Caveat
- Full `MyPluginTest` run can fail in the current environment due to existing index storage corruption (`testRename`, `CorruptedException`).
- Targeted settings-related regression tests pass.
