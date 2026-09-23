# 23: Search workspace symbols

**What to build:** Find declarations across project sources and configured libraries through Go to Symbol.

**Blocked by:** [16: Navigate to declarations](16-navigate-to-declarations.md).

**Status:** ready-for-agent

**Priority group:** 3 — Additional native editing.

- [ ] Index searchable functions, laws, datatypes and constructors including useful dotted-name components.
- [ ] Index data depends only on each file’s own contents; imported proof names resolve outside stub serialization.
- [ ] Results navigate to source and distinguish duplicate spellings; unsaved edits and changed library roots invalidate results.
- [ ] Global discoverability does not make a symbol available to ordinary completion; indexing-unavailable operation degrades safely.
- [ ] Fixtures cover unimported symbols, Base/cached libraries, duplicate names and navigation without full-project parsing per query.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [IntelliJ stub indexes guide](../../../.references/idea/19-stub-indexes.md)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
