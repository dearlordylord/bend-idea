# 41: Inspect compiler-provided goals and context

**What to build:** Inspect a proof goal and its local context where the selected compiler tooling explicitly supports that query.

**Blocked by:** [28: Navigate between laws, proofs and holes](28-navigate-between-laws-proofs-and-holes.md), [40: Show structured compiler diagnostics](40-show-structured-compiler-diagnostics.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Expose a capability-gated goal query at supported holes/locations and display expected type plus source-correlated local context.
- [ ] For at least one supported compiler/interface pairing, demonstrate real current-source goal/context data; do not substitute a fabricated context.
- [ ] Cancel obsolete queries, reject stale document versions and show unavailable state for absent context/spans or unsupported locations.
- [ ] Named/TODO holes retain incomplete status; fixtures and real adapter tests cover dependent contexts, rewrites and earlier compiler failures.

**Capability or scope constraint:** External prerequisite: a compatible supported way to retrieve goal/context information. Existing text output exposes a first named-hole goal only. If richer data is unavailable, report this prerequisite and do not mark a placeholder UI as completion.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [IntelliJ tool windows guide](../../../.references/idea/21-tool-windows.md)
