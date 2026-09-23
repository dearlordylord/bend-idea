# 05: Complete parameters, lambdas and local bindings

**What to build:** Receive local suggestions that follow lexical scope, shadowing and binding order.

**Blocked by:** [04: Complete current-file declarations](04-complete-current-file-declarations.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Earlier telescope parameters scope over later types and the body, but a parameter does not scope over its own type.
- [ ] Lambda and ordinary let bindings are offered only within their continuations; shadowed names resolve to the nearest binding.
- [ ] All parallel-let right-hand sides use the outer scope; new binders become available together afterward.
- [ ] Quantity and template annotations remain visible, and completion does not pretend to track resource consumption.
- [ ] Fixtures cover inline and parenthesized bodies, semicolons, unfinished RHS expressions, shadowing and parallel-let bindings.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
- [Quint references and rename prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/references)
