## 1. Runtime Boundary Refactor

- [x] 1.1 Extract and/or formalize interfaces for transport, project router, tool registry, and tool executor components.
- [x] 1.2 Refactor MCP request handling so HTTP transport delegates routing and tool resolution without tool-specific branching.
- [x] 1.3 Enforce project-scoped context propagation from startup/service registration through request dispatch.

## 2. Tool Registration and Discovery

- [x] 2.1 Define standardized tool registration model (stable ID, metadata, input schema contract, handler binding).
- [x] 2.2 Implement centralized tool registry used by discovery and invocation paths.
- [x] 2.3 Update tool discovery responses to be deterministic and registry-derived with stable metadata structure.

## 3. Compatibility and Concurrency Guarantees

- [x] 3.1 Add compatibility checks to ensure additive tool additions do not break existing tool contracts.
- [x] 3.2 Make registry lookup and dispatch path concurrency-safe for parallel invocations.
- [x] 3.3 Verify lifecycle contract remains intact: project open -> service ensureStarted -> router registration -> `/mcp` availability.

## 4. Validation and Regression Coverage

- [x] 4.1 Add/extend unit tests for registration contract validation and deterministic discovery output.
- [x] 4.2 Add integration tests covering invocation of existing + newly added tools in the same runtime.
- [x] 4.3 Add concurrency/isolation tests to validate no cross-project state leakage during parallel requests.
- [x] 4.4 Run project checks and fix regressions until build and tests pass for the new architecture.
