# 06: Complete match and do-block bindings

**What to build:** Complete pattern variables and monadic bindings in their actual cases and continuations.

**Blocked by:** [05: Complete parameters, lambdas and local bindings](05-complete-parameters-lambdas-and-local-bindings.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Constructor, tuple and nested pattern binders are scoped to their case; adjacent and nested cases do not leak bindings.
- [ ] Typed monadic and pure bindings in do blocks become visible only after their RHS; final expressions and returns remain distinguishable.
- [ ] An array write followed by a statement exposes the correct continuation binding for the array.
- [ ] Column-sensitive cases/do statements, inline forms and empty matches parse according to Bend rather than a generic Python block model.
- [ ] Fixtures assert inclusion and exclusion of names across branch boundaries, do statements, array rebinding and malformed nested bodies.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
