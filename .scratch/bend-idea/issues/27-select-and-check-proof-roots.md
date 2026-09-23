# 27: Select and check proof roots

**What to build:** Choose one or more proof roots independently of the active editor and check the intended loaded program.

**Blocked by:** [13: Check automatically after edits](13-check-automatically-after-edits.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Offer separate Check Current File and Check Proof Root actions with persisted selectable roots and sensible convention-based suggestions.
- [ ] Root identity is visible in results; checking a law file alone can remain incomplete even when a separate root fills its laws.
- [ ] Results and freshness are isolated across roots, including alternative implementations of the same law.
- [ ] Real compiler and editor tests cover successful/incomplete/failed roots, missing LAWS imports, root switching and dependency edits.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [Concrete laws and proof example](../../../.references/bend/demos/app_pong_game_2d)
- [Bend parser, loader and trusted checker](../../../.references/bend/bend2/bend.ts)
