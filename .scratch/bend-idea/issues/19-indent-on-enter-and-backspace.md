# 19: Indent on Enter and Backspace

**What to build:** Maintain appropriate Bend indentation while typing and deleting line breaks/leading whitespace.

**Blocked by:** [07: Complete laws and proof bindings](07-complete-laws-and-proof-bindings.md), [14: Assist brace and quote editing](14-assist-brace-and-quote-editing.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Enter indents declaration, case and do continuations appropriately and distinguishes inline/parenthesized forms.
- [ ] Backspace returns to the appropriate indentation level without disturbing significant token adjacency.
- [ ] Comments, blank lines, multiline literals and incomplete headers behave conservatively; default indentation is two spaces.
- [ ] Fixtures invoke actual editor actions over nested bodies, continued headers, empty matches and malformed input.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint editor actions](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/editor)
- [Quint formatting prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/formatter)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
