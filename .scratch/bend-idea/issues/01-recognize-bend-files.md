# 01: Recognize Bend files

**What to build:** Install the plugin and open Bend source with the correct language identity and light/dark file icons.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

**Priority group:** 1 — Bend VS Code coverage.

- [ ] Opening a Bend source file selects Bend 2 automatically and displays a suitable icon in both themes.
- [ ] The plugin loads without a Bend executable or JavaScript runtime and does not require commercial LSP support.
- [ ] A coherent IDE, JDK, Kotlin and Gradle combination is pinned and documented; the generated distribution installs in a sandbox IDE.
- [ ] An IntelliJ fixture verifies file recognition; a sandbox smoke check and Plugin Verifier cover the selected supported platform.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Quint build and registration reference](../../../.references/quint-idea/build.gradle.kts)
- [IntelliJ setup guide](../../../.references/idea/01-getting-started.md)
