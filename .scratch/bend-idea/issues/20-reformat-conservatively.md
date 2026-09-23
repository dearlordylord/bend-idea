# 20: Reformat conservatively

**What to build:** Apply Reformat Code to normalize safe spacing and indentation without changing Bend meaning.

**Blocked by:** [19: Indent on Enter and Backspace](19-indent-on-enter-and-backspace.md).

**Status:** ready-for-agent

**Priority group:** 2 — Quint IDEA coverage.

- [ ] Preserve significant adjacency/newlines around constructors, calls/indexes, quantities, rewrites, natural patterns, comparisons and nested type closers.
- [ ] Preserve comments, literal contents, declaration/import order and distinctions between operator namespace selection and type annotation.
- [ ] Uncertain/malformed regions remain unchanged; do not introduce expression wrapping or sorting; formatting is idempotent.
- [ ] Fixture tests verify text and surface-parse preservation, supplemented by pinned compiler before/after outcomes for whitespace-sensitive valid cases.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend formatter and its tests](../../../.references/bend/tools/bend-fmt-lsp)
- [Quint formatting prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/formatter)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
