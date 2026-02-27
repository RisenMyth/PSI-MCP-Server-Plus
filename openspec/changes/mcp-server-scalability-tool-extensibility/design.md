## Context

The plugin currently boots the MCP runtime from `MyProjectActivity`, then project services register through router/server components. This is sufficient for basic usage, but long-term growth requires explicit boundaries for request transport, project routing, tool registration, and execution. Without those boundaries, adding tools risks coupling core server flow to individual tool implementations and increasing regression risk.

The repository already separates startup, service, and mcp packages, which provides a strong baseline. This design formalizes those boundaries and defines extension points so tools can be added in a predictable, testable way.

## Goals / Non-Goals

**Goals:**
- Define a modular MCP server architecture with clear responsibilities across transport, router, registry, and executor layers.
- Define a standardized tool extension mechanism so new tools can be introduced with minimal changes to server core.
- Ensure request handling remains safe under concurrent/multi-project usage.
- Define compatibility and discovery requirements so clients can enumerate and use newly added tools reliably.
- Define validation expectations (unit/integration) to prevent regressions when extending tools.

**Non-Goals:**
- Rewriting all existing MCP code in a single change.
- Introducing a new external protocol beyond MCP.
- Changing user-facing plugin settings unrelated to server scalability/tool extensibility.
- Optimizing for distributed deployment; scope is IntelliJ plugin runtime.

## Decisions

1. **Layered runtime boundaries**
   - Decision: Split runtime responsibilities into transport (`PsiMcpHttpServer`), project routing (`PsiMcpRouterService`), tool registry, and tool executor components.
   - Rationale: Keeps core flow stable while tools evolve independently.
   - Alternative considered: Keep monolithic server/controller flow; rejected due to growth-related coupling.

2. **Declarative tool registration contract**
   - Decision: Tools are registered via a common contract (tool metadata + handler), and discovered from a centralized registry.
   - Rationale: New tools require registration, not core branching logic updates.
   - Alternative considered: Hardcoded routing branches per tool; rejected due to maintenance overhead and error-prone duplication.

3. **Versioned tool discovery and compatibility checks**
   - Decision: Tool list/metadata SHALL include stable identifiers and version-compatible contract fields to support additive changes.
   - Rationale: Enables safe tool additions without breaking existing MCP clients.
   - Alternative considered: Unversioned ad-hoc metadata; rejected due to client break risk.

4. **Concurrency-safe request dispatch**
   - Decision: Router/registry interactions SHALL be thread-safe and project-scoped; executor SHALL avoid mutable global tool state.
   - Rationale: Prevents cross-project leakage and race conditions as request volume/tool count grows.
   - Alternative considered: Shared mutable singleton state; rejected for correctness risks.

5. **Extension-focused test strategy**
   - Decision: Add tests that cover tool registration, discovery, and invocation isolation; include regression checks for existing tools when adding new ones.
   - Rationale: Scalability depends on safe incremental expansion.
   - Alternative considered: Manual validation only; rejected for insufficient safety as tool surface grows.

## Risks / Trade-offs

- **[Risk] Increased abstraction overhead** → **Mitigation**: Keep interfaces minimal and aligned to existing runtime flow.
- **[Risk] Migration complexity across current server code** → **Mitigation**: Introduce boundaries incrementally with compatibility wrappers where needed.
- **[Risk] Inconsistent tool metadata across implementations** → **Mitigation**: Enforce a shared registration model and validation tests.
- **[Risk] Performance impact from additional dispatch layers** → **Mitigation**: Keep dispatch path simple and benchmark request handling after refactor.

## Migration Plan

1. Introduce tool registration contract and centralized registry with no behavior changes.
2. Move current tool handling to registry-backed dispatch.
3. Isolate router responsibilities to project-aware dispatch only.
4. Add compatibility metadata checks in tool discovery responses.
5. Add/extend tests for registration, discovery, invocation, and concurrency.
6. Verify startup flow remains: `MyProjectActivity` -> project service ensureStarted -> router registration -> HTTP server handling.

Rollback strategy: retain prior dispatch path behind internal switch points during migration; if regressions appear, restore prior routing path while keeping non-breaking data model additions.

## Open Questions

- Should tool versioning be global at server level, per tool, or both?
- Do we require explicit capability tags for tool grouping (e.g., project, file, refactor domains)?
- Should registry support lazy tool initialization for startup performance?
