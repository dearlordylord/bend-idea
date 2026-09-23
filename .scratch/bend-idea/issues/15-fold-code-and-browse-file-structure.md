# 15: Fold code and browse file structure

**What to build:** Collapse source regions and navigate a structural outline of the current file.

**Blocked by:** [07: Complete laws and proof bindings](07-complete-laws-and-proof-bindings.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Outline entries include functions, laws, datatypes and constructors with meaningful labels and source navigation.
- [ ] Folding covers supported declaration bodies, cases, do blocks and multiline expressions using surface ranges.
- [ ] Incomplete code keeps valid neighboring entries/folds; string/comment content does not become a declaration.
- [ ] Fixtures verify outline contents, navigation offsets and fold boundaries in multiline and inline examples.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint editor actions](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/editor)
- [Quint structure view](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/structure)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
