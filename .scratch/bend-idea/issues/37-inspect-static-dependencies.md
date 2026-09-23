# 37: Inspect static dependencies

**What to build:** Inspect module imports and statically resolved named calls with source navigation.

**Blocked by:** [18: Find and highlight usages](18-find-and-highlight-usages.md), [23: Search workspace symbols](23-search-workspace-symbols.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Provide incoming/outgoing named-call relationships and import dependencies using resolved source identities.
- [ ] Handle cycles and multiple aliases without unbounded traversal or duplicate misleading nodes.
- [ ] Explain that higher-order calls and implicit sugar may not be fully represented; do not claim a complete runtime call graph.
- [ ] Fixtures verify edges/navigation across unsaved sources, roots and libraries and distinguish dynamic/unknown relationships.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
