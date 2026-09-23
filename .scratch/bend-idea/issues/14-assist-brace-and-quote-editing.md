# 14: Assist brace and quote editing

**What to build:** Use Bend-aware delimiter matching, insertion and paired deletion while editing.

**Blocked by:** [07: Complete laws and proof bindings](07-complete-laws-and-proof-bindings.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Parentheses, brackets and braces match and pair in valid contexts; quote handling respects escapes and literal state.
- [ ] Angle brackets are matched as type arguments only where the parsed context supports that interpretation, not arbitrary comparisons/shifts.
- [ ] Backspace and overtyping handle paired delimiters without deleting unrelated text or breaking unfinished code.
- [ ] Editor action fixtures cover empty pairs, nested generics, comparisons, strings, comments and incomplete syntax.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint editor actions](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/editor)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
