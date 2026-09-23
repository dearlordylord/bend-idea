# 32: Run Bend explicitly

**What to build:** Run a Bend root through an explicit IDE run configuration with useful process controls.

**Blocked by:** [12: Check unsaved dependency graphs](12-check-unsaved-dependency-graphs.md).

**Status:** ready-for-agent

**Priority group:** 4 — Bend workflows and execution.

- [ ] Configure root, executable, working directory, environment and correctly serialized program arguments; document whether relevant documents are saved or fully snapshotted.
- [ ] Provide standard Run, Stop and Rerun controls plus console output and conservative source links.
- [ ] Preserve pure-main normalization versus IO-main behavior of the selected compiler; execution errors do not become background-check success.
- [ ] Explicit run may execute main, while background checking remains check-only; real process tests cover arguments, paths, save policy, stop and exit codes.

**Shared constraints:** Follow the [approved specification](../../../SPEC.md) and [ticket working agreement](../README.md#working-agreement). Keep the feature usable without optional future capabilities; preserve the declared scope, lifecycle/cancellation requirements and the approved testing boundaries. Do not modify the protected Bend core.

**Context and prior art:**

- [Specification, decisions and pinned source links](../../../SPEC.md)
- [Original language/design investigation](../../../BEND_IDEA_DESIGN.md)
- [Bend CLI checking and execution contract](../../../.references/bend/bend2/main.ts)
- [Bend compiler and runtimes](../../../.references/bend/bend2/comp.ts)
- [IntelliJ run configurations guide](../../../.references/idea/22-run-configurations.md)
