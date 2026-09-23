# 38: Create conventional files from templates

**What to build:** Create a Bend module or conventional law/proof pair using editable file templates.

**Blocked by:** [03: Complete keywords and expand snippets](03-complete-keywords-and-expand-snippets.md), [29: Generate a law implementation skeleton](29-generate-a-law-implementation-skeleton.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Provide a module template and a linked law/proof pair using valid imports and aliases.
- [ ] Reuse established snippet/skeleton conventions and avoid overwriting existing files or creating conflicting declarations.
- [ ] Leave proof bodies explicitly unfinished and navigate to editable names/holes after creation.
- [ ] Editor fixtures verify created contents, path choices, collisions, navigation and incomplete proof status.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend language guide](../../../.references/bend/guide/GUIDE.md)
- [VS Code completion and imports implementation](../../../.references/bend-vscode/src)
