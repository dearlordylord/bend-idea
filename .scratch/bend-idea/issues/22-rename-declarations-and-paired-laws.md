# 22: Rename declarations and paired laws

**What to build:** Rename functions, datatypes, constructors and logical law symbols consistently across writable project sources.

**Blocked by:** [21: Rename locals and aliases](21-rename-locals-and-aliases.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Update matching declarations and references across import aliases while preserving literal dotted-name semantics.
- [ ] Pair a law and its relevant implementation declarations; do not mechanically rename independent parameter binders in law and proof.
- [ ] Keep constructor/type namespaces distinct and handle multiple candidate proof roots without treating every candidate as a duplicate.
- [ ] Preview multi-file changes, reject collisions/read-only library edits and support complete undo.
- [ ] Fixtures cover cross-file fills, independent proof implementations, dotted names, same-spelling categories and captured/imported names.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
- [Quint IntelliJ behavior fixtures](../../../.references/quint-idea/src/test/kotlin/com/dearlordylord/quint/idea)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
