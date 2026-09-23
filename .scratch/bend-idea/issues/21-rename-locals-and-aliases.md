# 21: Rename locals and aliases

**What to build:** Rename a local binding or import alias with correct references, conflict handling and undo.

**Blocked by:** [18: Find and highlight usages](18-find-and-highlight-usages.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Standard rename updates only references belonging to the selected local or alias, preserving dotted member spelling.
- [ ] Detect capture, invalid names and collisions before applying edits; provide preview and undoable changes.
- [ ] Handle telescope, lambda, case, do, let and rewrite binders without coupling unrelated or shadowed variables.
- [ ] Fixtures invoke rename from declarations and references, verify conflicts/undo, and cover unsaved files and alias-qualified members.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
- [Quint IntelliJ behavior fixtures](../../../.references/quint-idea/src/test/kotlin/com/dearlordylord/quint/idea)
- [IntelliJ refactoring guide](../../../.references/idea/16-refactoring.md)
