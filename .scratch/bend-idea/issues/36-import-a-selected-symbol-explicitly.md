# 36: Import a selected symbol explicitly

**What to build:** Choose an unimported declaration and deliberately add an appropriate import and reference spelling.

**Blocked by:** [23: Search workspace symbols](23-search-workspace-symbols.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Offer project/library candidates including constructors and Base with source context to disambiguate duplicates.
- [ ] Choose or request a conflict-free file-local alias and update the selected reference with its proper dotted suffix.
- [ ] Insert into the valid leading import region while preserving existing ordering and proof/Base effects; never remove imports automatically.
- [ ] Fixture/compiler cases cover alias collisions, existing imports, constructor names and explicit undoable insertion; ordinary completion stays name-only.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ completion guide](../../../.references/idea/14-code-completion.md)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
