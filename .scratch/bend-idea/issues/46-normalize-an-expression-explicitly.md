# 46: Normalize an expression explicitly

**What to build:** Request bounded expression normalization and inspect its result without blocking the editor.

**Blocked by:** [40: Show structured compiler diagnostics](40-show-structured-compiler-diagnostics.md), [42: Inspect actual expression types](42-inspect-actual-expression-types.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Offer normalization only at supported source expressions using the selected root/compiler context and capability negotiation.
- [ ] Run in a killable isolated worker with time/output limits and cancellation; dead/unsafe divergence must not freeze the IDE.
- [ ] Present normalized output as display data, not automatically insertable source; source changes invalidate its association.
- [ ] Real adapter/editor tests cover a terminating expression, unsupported context, long/diverging reduction, cancellation and cleanup.

**Capability or scope constraint:** External prerequisite: supported evaluation in the expression’s actual context. A type query alone is insufficient to safely reconstruct that context.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend compiler and runtimes](../../../.references/bend/bend2/comp.ts)
