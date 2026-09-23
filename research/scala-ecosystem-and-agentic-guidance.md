# Scala 3 ecosystem and agent workflows

Checked 2026-09-23 against first-party project documentation, release pages, and this repository. This repo is a Scala 3 IntelliJ plugin built with Gradle; it currently pins Scala 3.3.7 and JDK 21. This is a decision note, not a proposal to change dependencies.

## Scala and library choices

### The current Scala baseline

Scala 3.9.0 is the new LTS line; 3.10.0-RC2 is a prerelease. The Scala team recommends 3.9 as the baseline for conservative production users, succeeding 3.3 LTS. The team also calls out a migration boundary: artifacts compiled with 3.9 cannot be used by projects compiled with 3.3. This plugin is an application rather than a published Scala library, but moving the compiler and bundled Scala runtime still merits a focused build, fixture, and packaging check. [Scala 3.9 LTS announcement](https://scala-lang.org/news/3.9/), [Scala 3 releases](https://github.com/scala/scala3/releases), [Scala 3 binary compatibility policy](https://docs.scala-lang.org/overviews/core/binary-compatibility-of-scala-releases.html)

JetBrains recommends the IntelliJ Platform Gradle Plugin for building IntelliJ plugins, and that plugin supplies the platform test, packaging, signing, and compatibility-verification tasks. Keep Gradle as the product build. Scala CLI became the official `scala` runner in Scala 3.5; its maintainers explicitly say it is not intended for multi-module projects or extensible build tasks, so it is useful for scratch repros and small scripts, not a replacement here. [JetBrains plugin development introduction](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html), [Scala CLI README](https://github.com/VirtusLab/scala-cli)

### Fit with the current repository

[`gradle.properties`](../gradle.properties) selects Scala 3.3.7, while [`build.gradle.kts`](../build.gradle.kts) applies the Scala and IntelliJ Platform Gradle plugins, selects JDK 21, and includes `architectureTest` in `check`. The build declares no Cats, effect-runtime, or Scala agent-tooling dependency. [`ARCHITECTURE.md`](../ARCHITECTURE.md) places source, workspace, and analysis policy in shared owners; [`BendCheckSession`](../src/main/scala/com/dearlordylord/bend/idea/adapters/intellij/BendCheckSession.scala), [`BendBackgroundChecking`](../src/main/scala/com/dearlordylord/bend/idea/adapters/intellij/BendBackgroundChecking.scala), and [`BendBoundedProcess`](../src/main/scala/com/dearlordylord/bend/idea/adapters/process/BendBoundedProcess.scala) currently own check scheduling, cancellation, disposal, and subprocess cleanup at the adapter boundary. This supports evaluating any new runtime against a concrete lifecycle problem rather than adopting one for the policy packages.

The [CI workflow](../.github/workflows/verify.yml) invokes `check`, `buildPlugin`, and `verifyPlugin`, but it does not provision the pinned Bend reference checkout or a pinned Bun runtime. The [real-compiler fixtures](../src/test/scala/com/dearlordylord/bend/idea/adapters/cli/BendCliGraphCheckTest.scala) read `.references/bend/bend2/main.ts` and run it through `npx --yes bun`; [`.gitignore`](../.gitignore) excludes `.references/`. **Inference from the checked-in files:** invoking `check` on a fresh runner cannot establish the required pinned real-compiler evidence until those inputs are provisioned. A future tooling or Scala upgrade should report editor, architecture, compiler, packaging, and verifier results separately, and identify the compiler/runtime revision used for the compiler tests. This is the A6–A7 verification boundary in [`ARCHITECTURE.md`](../ARCHITECTURE.md), not an observed CI run.

### Functional libraries and effect runtimes

Cats is still maintained and current. The latest Cats Core release is 2.13.0, published for Scala 2.12, 2.13, and Scala 3.3. Cats is a modular foundation of type classes and functional data, not a requirement for writing functional Scala. Its separate Cats Effect library supplies `IO`, concurrency, and resource management. [Cats 2.13.0 release](https://github.com/typelevel/cats/releases/tag/v2.13.0), [Cats FAQ and design](https://typelevel.org/cats/faq.html)

| Option | Maintainer status checked | Fit for this plugin |
|---|---|---|
| **Cats Core** | 2.13.0 is the latest release; the release notes list Scala 3.3 artifacts. | Consider if shared type classes, validated accumulation, or Cats ecosystem interop solve a real need. Existing Scala 3 ADTs, `Option`, and `Either` already cover the project's core modeling style. |
| **Cats Effect** | 3.7.1 is the latest stable release; it is binary-compatible across the 3.x line. The project docs list Scala 3.2 artifacts. | Mature choice when an application needs an `IO` runtime, cancellation, concurrency, and `Resource`. Here, project-owned adapters handle worker scheduling and cancellation under IntelliJ's lifecycle and read/write-action rules, so adding a second runtime has an integration cost. Consider only for a bounded adapter if process orchestration grows enough to justify it. |
| **ZIO** | 2.1.26 is the latest release. ZIO describes itself as a zero-dependency library for asynchronous and concurrent Scala programming and has its own testing and service model. | A capable alternative effect/runtime ecosystem, not a small Cats add-on. Evaluate only if the project deliberately adopts its runtime and service model end to end. |
| **Ox** | 1.0.8 is the latest release as of this check. It targets Scala 3 and JDK 21+, with direct-style structured concurrency, streaming, and resiliency. | Worth a prototype if direct-style JVM concurrency is important. Its thread-interruption/scoped-fork model still needs a deliberate bridge to IntelliJ lifecycle and platform-thread rules. |
| **Kyo** | The latest release is 1.0.0-RC6; the 1.0 line is still in release-candidate status. Kyo uses typed effect rows and now includes AI, MCP, and LSP/compiler modules. Its README warns that IntelliJ may not fully support the latest Scala 3 features and recommends Metals with sbt BSP for Kyo development. | The most experimental Scala 3 effects/LLM direction in this scan. Watch and experiment separately; it is not a low-risk plugin dependency today. |

Sources: [Cats Effect releases and Scala versions](https://github.com/typelevel/cats-effect/releases), [Cats Effect project overview](https://github.com/typelevel/cats-effect), [ZIO releases](https://github.com/zio/zio/releases), [ZIO README](https://github.com/zio/zio), [Ox 1.0.8 release](https://github.com/softwaremill/ox/releases/tag/v1.0.8), [Ox README](https://github.com/softwaremill/ox), [Kyo releases](https://github.com/getkyo/kyo/releases), [Kyo README and IDE requirements](https://github.com/getkyo/kyo).

**Recommendation:** keep shared policies as small Scala 3 data and functions, as the architecture already specifies. Add Cats Core only where its algebra/data APIs are useful. Do not add Cats Effect, ZIO, Ox, and Kyo as a bundle: they represent different runtime and concurrency models. If plugin-owned asynchronous work becomes hard to express with the IntelliJ APIs, compare one runtime inside a narrow adapter and test cancellation, disposal, and write/read-action boundaries before adopting it.

## Agent harnesses and Scala project guidance

### Practical Scala-aware tools

**Metals MCP is the strongest Scala-specific agent integration found.** Its current docs describe compiler and test execution, module import, symbol search/inspection and documentation, dependency lookup, formatting, and refactoring; a standalone `metals-mcp` server is also available. Metals' build matrix includes Gradle, but this particular Gradle build combines the Scala plugin with IntelliJ platform fixtures and packaging. Treat Metals as a semantic assistant to investigate: verify that it imports this workspace correctly and use the Gradle checks as the product validation commands, subject to the real-compiler fixture prerequisite above. [Metals MCP](https://scalameta.org/metals/docs/features/mcp/), [Metals build-tool support](https://scalameta.org/metals/docs/build-tools/overview/)

**Orca is an emerging Scala-authored coding-agent workflow harness.** VirtusLab's project provides Scala scripts and a CLI for resumable, configurable plan/implement/review flows across agent CLIs. It is pre-1.0 (the README currently shows `orca` 0.1.9) and warns that write-capable agent tools are auto-approved by default, so use only with an explicit sandbox/configuration decision. It orchestrates coding agents; it is not Scala compiler intelligence. [Orca README](https://github.com/VirtusLab/orca), [Orca releases](https://github.com/VirtusLab/orca/releases)

**Metals is different from an agent framework:** it supplies Scala build/type/symbol evidence over MCP to a harness such as Codex, Claude Code, or Cursor. For this plugin, this separation is useful: a future agent can ask the IDE/compiler for facts while existing Gradle tasks remain the validation source.

Two early projects are worth watching, not treating as established tooling. [Scala Semantic Harness](https://github.com/DmytroMitin/scala-semantic-harness) describes itself as an evidence layer for Scala agents, built around compiler/build/test truth and MCP. Kyo's [kyo-ai](https://github.com/getkyo/kyo) and `kyo-mcp` are Scala application libraries for building LLM agents and MCP services; they are not repository coding-agent harnesses, and Kyo itself is still RC6.

### What Scala repositories put in `AGENTS.md`

The concrete examples have useful common structure:

- The [Scala 2.13 compiler guide](https://github.com/scala/scala/blob/2.13.x/AGENTS.md) lists repo facts and commands, explains its JUnit/ScalaCheck/Partest split, and calls out binary compatibility, MiMa, type-inference, and compiler-test pitfalls. Its test taxonomy is specific to that compiler repository, not a template to copy wholesale into a plugin repo.
- [Scala CLI's guide](https://github.com/VirtusLab/scala-cli/blob/main/AGENTS.md) acts as a short entry point to deeper developer and architecture docs, gives executable Mill/test/format commands, and links task-specific skills. The repo's [directive skill](https://github.com/VirtusLab/scala-cli/blob/main/agentskills/adding-directives/SKILL.md) and [integration-test skill](https://github.com/VirtusLab/scala-cli/blob/main/agentskills/integration-tests/SKILL.md) provide exact task steps only when relevant.
- [Kyo's guide](https://github.com/getkyo/kyo/blob/main/AGENTS.md) is compact: read its contribution guide, diagnose failures before changing tests, and assert concrete behavior instead of weakening checks. [Scala Semantic Harness's guide](https://github.com/DmytroMitin/scala-semantic-harness/blob/main/AGENTS.md) states the project goal, Scala 3 style, machine-facing JSON preference, resource limits, documentation updates, and validation rules.

The open [Agent Skills format](https://github.com/agentskills/agentskills) packages a task-specific workflow in a directory with a `SKILL.md` manifest and optional reference files, scripts, or templates. Skills are discovered by name/description and loaded on demand; they complement rather than replace repository-wide `AGENTS.md` guidance. [OpenAI skills guide](https://developers.openai.com/api/docs/guides/tools-skills)

**Recommendation for this repo:** its existing `AGENTS.md`, `ARCHITECTURE.md`, and `REVIEWER.md` already provide the map, architecture, and review contracts. Preserve that separation. Add a skill only if a repeatable specialized procedure is too detailed for the root guide—for example, IntelliJ editor-fixture behavior or real pinned Bend subprocess/snapshot testing—and keep its `description` specific enough to activate only for that work. The project does not need an agent framework or new skill merely because those are available.

## Sources checked

Only first-party documentation, source repositories, and release pages were used. Versions above are the latest stable or prerelease tags shown on those sources on the check date; they are research markers, not dependency recommendations.
