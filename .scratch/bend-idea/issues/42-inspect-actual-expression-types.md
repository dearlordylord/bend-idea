# 42: Inspect actual expression types

**What to build:** View compiler-derived expression types in hover and optional inlays at reliably mapped source locations.

**Blocked by:** [17: Show Quick Documentation](17-show-quick-documentation.md), [40: Show structured compiler diagnostics](40-show-structured-compiler-diagnostics.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Negotiate type-query support and demonstrate real expression-type mapping for a pinned compatible compiler/interface.
- [ ] Show dependent substitutions from the compiler, distinguish source signatures from expression results and omit ambiguous synthetic spans.
- [ ] Hover/inlays use current document versions, stay bounded and cancel on edits/settings changes.
- [ ] Adapter/editor tests cover applications, dependent binders, failed checks, stale mappings and unsupported positions without invented types.

**Capability or scope constraint:** External prerequisite: reliable compiler source-to-type data. Checked terms alone may have broad or missing spans; arbitrary-expression coverage is not guaranteed by structured first-error diagnostics.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Quint documentation presentation](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/documentation)
- [IntelliJ language feature guide](../../../.references/idea/08-custom-language-overview.md)
