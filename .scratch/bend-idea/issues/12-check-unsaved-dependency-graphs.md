# 12: Check unsaved dependency graphs

**What to build:** Check an unsaved root together with all reachable unsaved dependencies and show errors on the correct original source where identifiable.

**Blocked by:** [09: Complete imported and qualified names](09-complete-imported-and-qualified-names.md), [11: Check a single unsaved file explicitly](11-check-a-single-unsaved-file-explicitly.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Snapshot the entire import graph using open documents first, preserving order, canonical identity and compiler namespace constraints.
- [ ] Preserve the PROOF/sibling-LAWS presence guard even when the required import is absent; use the selected compiler’s matching Base.
- [ ] Maintain original/snapshot URI, namespace and rewritten-range mappings; source copies preserve diagnostic line counts and leave originals untouched.
- [ ] Missing cached imports produce local diagnostics without network access or cache mutation; ambiguous text excerpts fall back to the root rather than guessing.
- [ ] Real compiler tests cover unsaved imported errors, absolute/hash/relative imports, symlink identities, identical excerpts, PROOF guards, no-main execution, offline behavior and cleanup.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [VS Code snapshot checker](../../../.references/bend-vscode/src/checker.cjs)
- [VS Code behavior and compiler tests](../../../.references/bend-vscode/test)
