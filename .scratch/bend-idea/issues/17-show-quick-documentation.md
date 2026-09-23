# 17: Show Quick Documentation

**What to build:** Read signatures, source comments and law specifications from references without leaving the editor.

**Blocked by:** [16: Navigate to declarations](16-navigate-to-declarations.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Quick Documentation shows resolved source signatures and comments with navigation links to the declaration and implementation where available.
- [ ] Law-backed definitions display the specification while retaining implementation parameter names and source context.
- [ ] Quantity/template annotations and datatype/constructor distinctions are preserved; arbitrary-expression inferred types are not fabricated.
- [ ] Fixtures cover local/imported/Base references, law fills, missing documentation, unsaved changes and unavailable resolution.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint documentation presentation](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/documentation)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
