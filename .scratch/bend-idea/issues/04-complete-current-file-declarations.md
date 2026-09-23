# 04: Complete current-file declarations

**What to build:** Complete functions, datatypes and constructors from the current unsaved file, with signatures and source comments.

**Blocked by:** [03: Complete keywords and expand snippets](03-complete-keywords-and-expand-snippets.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] A tolerant surface parser retains declaration names, parameters, constructor fields and source text without requiring imported files or compiler execution.
- [ ] Completion distinguishes datatype and constructor symbols even when their spellings match, respects declaration order, and inserts names without forcing calls.
- [ ] Suggestions display declared signatures, quantity/template markers and contiguous source comments.
- [ ] Malformed bodies or unfinished headers recover at credible declaration boundaries; subsequent valid declarations remain discoverable and parsing always advances.
- [ ] Fixtures demonstrate completion before and after unsaved edits, multiline headers, literal dotted names and malformed neighboring declarations.

**Capability or scope constraint:** Introduce only the source structure needed by this slice and extend it in the following scope tickets. Do not create an alternative regex semantic engine.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [Quint parser integration](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/parser)
- [IntelliJ parser and PSI guide](../../../.references/idea/11-parser-and-psi.md)
