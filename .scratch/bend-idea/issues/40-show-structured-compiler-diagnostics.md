# 40: Show structured compiler diagnostics

**What to build:** Use a compatible compiler adapter to show source-versioned structured diagnostics while retaining the existing check-only workflow.

**Blocked by:** [12: Check unsaved dependency graphs](12-check-unsaved-dependency-graphs.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Define and negotiate adapter/compiler protocol versions and supported operations; incompatible installations keep the established CLI checker available.
- [ ] For a pinned supported pairing, produce a real first-error structured result with original source identity/ranges where available, mapping loader/snapshot edits explicitly.
- [ ] Preserve complete CLI validity conditions, incomplete/open-hole status and unsafe/foreign reporting; absent spans stay explicit.
- [ ] Keep adapter work isolated, bounded and cancellable; serialize display data rather than mutable compiler books or closures.
- [ ] End-to-end tests compare structured results with the CLI on unsaved graphs, missing spans, Unicode offsets, incompatibility and timeout.

**Capability or scope constraint:** Do not modify the protected trusted core. A wrapper may expose existing errors; richer capabilities need an approved compatible upstream interface. This ticket must deliver an actual diagnostic path, not only a schema.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [Bend compiler and runtimes](../../../.references/bend/bend2/comp.ts)
