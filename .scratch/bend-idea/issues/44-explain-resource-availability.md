# 44: Explain resource availability

**What to build:** Display supported compiler-derived information about affine resources at the current proof or expression location.

**Blocked by:** [41: Inspect compiler-provided goals and context](41-inspect-compiler-provided-goals-and-context.md), [42: Inspect actual expression types](42-inspect-actual-expression-types.md).

**Status:** ready-for-agent

**Priority group:** 5 — Compiler-backed semantic assistance.

- [ ] Use explicit compiler usage/demand data accounting for live/dead positions, branch joins and reusable promotions.
- [ ] Distinguish erased, affine and reusable bindings; legal unused affine values are not reported as errors.
- [ ] Display unavailable or partial information honestly and never infer consumed status from plain occurrence counts.
- [ ] Real adapter/editor tests cover branches, proofs/types, closures, reusable binders and source edits.

**Capability or scope constraint:** External prerequisite: a supported resource-usage query. Goals and type maps do not themselves provide remaining-resource state.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
