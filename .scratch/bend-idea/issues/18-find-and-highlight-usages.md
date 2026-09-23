# 18: Find and highlight usages

**What to build:** Find actual references to a binding within a file and across the project.

**Blocked by:** [16: Navigate to declarations](16-navigate-to-declarations.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Standard usage highlighting and Find Usages operate on resolved binding identity, not raw text matches.
- [ ] Qualified and literal dotted names are discoverable; alias-prefix usages remain separate from member-symbol usages.
- [ ] Law/implementation grouping and constructor/type distinctions avoid missing references or including unrelated declarations.
- [ ] Workspace/library search stays bounded and index-safe; edited documents take precedence over stale source data.
- [ ] Fixtures cover shadowing, identical names across modules, aliased references, paired laws and absent usages.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
- [Quint IntelliJ behavior fixtures](../../../.references/quint-idea/src/test/kotlin/com/dearlordylord/quint/idea)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
