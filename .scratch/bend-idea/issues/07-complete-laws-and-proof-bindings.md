# 07: Complete laws and proof bindings

**What to build:** Complete law-backed definitions and proof-local names without duplicating symbols or inventing scope.

**Blocked by:** [06: Complete match and do-block bindings](06-complete-match-and-do-block-bindings.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] A same-file law and its filling definition yield one logical completion with the law specification and implementation source preserved.
- [ ] Universal/existential clauses, dependent arrows and law where clauses scope binders correctly; bare fill parameters use the law signature.
- [ ] Rewrite motive placeholders and named equation binders are visible only inside the motive; ordinary underscore binders remain nonbinding.
- [ ] Holes, rewrites, equality propositions and operator annotations retain distinct surface structure; open laws are not labeled proved.
- [ ] Fixtures cover law-fill ordering, type-valued laws, nested proof binders, named/TODO holes and incomplete proof forms.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
