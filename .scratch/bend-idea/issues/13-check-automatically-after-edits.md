# 13: Check automatically after edits

**What to build:** Receive current compiler feedback after a typing pause, including changes in imported files.

**Blocked by:** [12: Check unsaved dependency graphs](12-check-unsaved-dependency-graphs.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Debounce edits, cancel superseded processes and reject results whose root/dependency/document/compiler versions changed.
- [ ] Invalidate affected roots on import, Base, compiler and setting changes; one root’s result cannot clear another root’s diagnostics.
- [ ] Expose checking/current/stale/incomplete/failed/unavailable/timeout status and background-checking enablement; never preserve a stale success as current.
- [ ] Process work stays outside IDE read locks/UI work; project close and plugin unload cancel jobs and remove snapshots/listeners.
- [ ] Fixtures and process tests cover rapid edits, dependency-only changes, cancellation races, multiple open roots, disabling checks and disposal.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [VS Code diagnostics scheduling](../../../.references/bend-vscode/src/diagnostics.cjs)
- [Quint external checking prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/annotator)
- [IntelliJ threading guide](../../../.references/idea/05-threading-model.md)
