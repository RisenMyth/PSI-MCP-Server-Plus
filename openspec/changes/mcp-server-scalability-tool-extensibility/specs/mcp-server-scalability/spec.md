## ADDED Requirements

### Requirement: Modular MCP runtime boundaries
The MCP server runtime SHALL separate transport, project routing, tool registry, and tool execution responsibilities into distinct components with explicit interfaces.

#### Scenario: Request dispatch follows defined boundaries
- **WHEN** an MCP request is received by the HTTP server
- **THEN** the server delegates routing and tool selection through dedicated router/registry components without embedding tool-specific branching in transport code

### Requirement: Project-scoped routing isolation
The routing layer SHALL resolve and dispatch requests using project-scoped context so requests from one project cannot be executed against another project's state.

#### Scenario: Concurrent requests from different projects remain isolated
- **WHEN** requests for two different project contexts are processed concurrently
- **THEN** each request is dispatched using its own project context and no cross-project state leakage occurs

### Requirement: Concurrency-safe registry and dispatch
Tool registry lookups and dispatch operations SHALL be safe under concurrent access.

#### Scenario: Parallel tool invocations do not corrupt registry state
- **WHEN** multiple MCP tool invocations occur in parallel
- **THEN** registry reads and dispatch operations complete without data races or inconsistent tool resolution

### Requirement: Stable startup-to-server lifecycle contract
The startup lifecycle SHALL preserve the runtime path from project startup through service registration to HTTP server availability.

#### Scenario: Server becomes available through lifecycle chain
- **WHEN** a project is opened
- **THEN** startup activity triggers project service startup, project registration is completed, and MCP HTTP endpoints become available for requests
