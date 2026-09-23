# 33: Build and inspect generated output

**What to build:** Build a native executable or emit C/JavaScript and open generated source from an IDE configuration.

**Blocked by:** [32: Run Bend explicitly](32-run-bend-explicitly.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Provide explicit native-build and C/JS-emission output choices with correctly passed compiler arguments.
- [ ] Validate output paths against inputs and directories, refresh generated artifacts and offer opening emitted source.
- [ ] Native execution settings expose supported threads/GPU options and keep companion artifacts together when required; report missing backend tools clearly.
- [ ] Real compiler tests verify emitted outputs and error handling; supported-environment smoke checks cover native build/run without claiming a debugger.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [Bend compiler and runtimes](../../../.references/bend/bend2/comp.ts)
- [IntelliJ run configurations guide](../../../.references/idea/22-run-configurations.md)
