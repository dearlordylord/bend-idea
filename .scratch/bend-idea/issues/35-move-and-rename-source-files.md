# 35: Move and rename source files

**What to build:** Reorganize writable project sources while updating affected module and foreign references.

**Blocked by:** [22: Rename declarations and paired laws](22-rename-declarations-and-paired-laws.md), [34: Complete and navigate foreign imports](34-complete-and-navigate-foreign-imports.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Standard file move/rename previews affected relative imports and supported foreign paths and updates them consistently.
- [ ] Preserve canonical identity and namespace constraints; reject ambiguous or unsupported rewrites rather than silently changing the program.
- [ ] Invalidate affected checking roots and preserve import order; do not mutate cached read-only packages.
- [ ] Fixtures verify multi-directory moves, aliases, source collisions, undo, and compiler outcomes for representative complete graphs.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
- [IntelliJ refactoring guide](../../../.references/idea/16-refactoring.md)
