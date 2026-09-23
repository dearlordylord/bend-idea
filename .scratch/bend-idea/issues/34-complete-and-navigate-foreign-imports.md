# 34: Complete and navigate foreign imports

**What to build:** Complete and follow foreign C/JavaScript source paths from a foreign definition.

**Blocked by:** [16: Navigate to declarations](16-navigate-to-declarations.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Foreign-body paths are distinguished from leading Bend module imports and resolve relative to the correct source context.
- [ ] Offer matching local foreign-source candidates and navigate existing references without executing them.
- [ ] Unreadable/missing paths and unsaved edits behave predictably; foreign signatures remain visible in ordinary documentation.
- [ ] Fixtures cover multiple foreign paths, relative paths, missing files and distinction from string literals elsewhere.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
- [Installed-source reference Base](../../../.references/bend/bend2/base.bend)
- [IntelliJ references and navigation guide](../../../.references/idea/13-references-and-navigation.md)
