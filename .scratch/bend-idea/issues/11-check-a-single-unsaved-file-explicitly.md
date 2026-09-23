# 11: Check a single unsaved file explicitly

**What to build:** Invoke Check Current File and see the compiler result for unsaved source using Base or no module imports.

**Blocked by:** [08: Configure libraries and complete Base names](08-configure-libraries-and-complete-base-names.md).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Probe and require documented check-only capability; unsupported compilers produce a setup message before any source invocation.
- [ ] Check a temporary source snapshot, preserve original files, disable package downloads/update checks, enforce timeout/output limits and clean up on all exits.
- [ ] Unsupported non-Base dependency graphs are reported explicitly pending graph support; they are never checked from stale disk implicitly.
- [ ] Display compiler errors in the editor/Problems with full details and conservative line attribution; distinguish success, incomplete, failed, unavailable, timeout and unsafe/foreign reliance.
- [ ] Real Bend tests use a side-effecting main and prove it never executes; cover unsaved errors, holes, nonzero exit, missing compiler and snapshot cleanup.

**Capability or scope constraint:** The CLI completeness contract includes open claims, TODO holes and reserved-name validation, not just the core checker return value.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [VS Code snapshot checker](../../../.references/bend-vscode/src/checker.cjs)
- [Quint external checking prior art](../../../.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea/annotator)
