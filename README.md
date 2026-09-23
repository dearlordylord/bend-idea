# Bend IDEA

A native IntelliJ IDEA plugin for Bend 2, implemented in Scala 3. It recognizes `.bend` files, supplies light/dark file icons, highlights source, supports standard hash line commenting, and provides lexical keyword completion with six editable snippets. Declaration parsing, name completion and compiler integration follow in later issues.

- [Implementation specification and roadmap](https://github.com/dearlordylord/bend-idea/issues/1)
- [Language and platform design investigation](BEND_IDEA_DESIGN.md)
- [Architecture and package boundaries](ARCHITECTURE.md)
- [Implementation working agreement](AGENTS.md)
- [Review procedure](REVIEWER.md)
- [Domain glossary](CONTEXT.md)
- [Issue index and priority order](.scratch/bend-idea/README.md)
- [GitHub issues](https://github.com/dearlordylord/bend-idea/issues)

## Working agreement

Implementation work follows [AGENTS.md](AGENTS.md) and the contracts in [ARCHITECTURE.md](ARCHITECTURE.md). Before completing a change, apply [REVIEWER.md](REVIEWER.md) and report the actual validation evidence. Scala 3 supersedes earlier Kotlin recommendations.

## Scaffold / task 0

[Issue #2](https://github.com/dearlordylord/bend-idea/issues/2) owns the scaffold and file recognition; a separate prerequisite issue is unnecessary. This slice introduces only `syntax` production code, plugin registration, an editor fixture and build/architecture verification. Later packages should appear with their first actual consumer.

| Component | Pinned baseline |
|---|---|
| IntelliJ IDEA Community | 2025.1 (`251.23774.435`); declared support `251.*` |
| Build/target JDK | 21 (full JDK, not a JRE) |
| Scala | 3.3.7; bundled Scala 2.13.16 standard library dependency |
| Gradle wrapper | 9.4.0, distribution checksum pinned |
| IntelliJ Platform Gradle plugin | 2.12.0 |
| Plugin Verifier | 1.410 |
| Test libraries | JUnit 4.13.2, ArchUnit 1.4.1 |

The initial IDE baseline follows the supplied integration reference and is checked by Plugin Verifier. New IDE release lines require deliberate compatibility testing before widening the declared range. The Scala runtime ships in the plugin; users do not need the Scala plugin, Bend, Node or Bun for file recognition.

## Build and verify

Set `JAVA_HOME` to a full JDK 21. The first build downloads Gradle, the IDE SDK and Maven dependencies; later builds reuse their caches. Windows users can use `gradlew.bat`.

```sh
./gradlew check                 # editor fixtures and compiled architecture checks
./gradlew architectureTest      # dependency rules and their negative Scala fixture
./gradlew buildPlugin           # build/distributions/bend-idea-0.1.0-SNAPSHOT.zip
./gradlew verifyPlugin          # binary compatibility with the pinned IDEA version
./gradlew runIde                # launch an isolated development IDE; requires a display
```

Install the ZIP using **Settings → Plugins → Install Plugin from Disk** in a supported IDE, or use `runIde` with its prepared sandbox. Kotlin files here configure Gradle; all plugin and test implementation is Scala 3.

[CI](.github/workflows/verify.yml) runs `check buildPlugin verifyPlugin` on pull requests and pushes to master, and uploads reports and the plugin ZIP. Configure its `verify` job as a required branch check when publishing the workflow; repository rulesets are not set by this source file.

## Architecture checks

[ArchitectureRules.scala](src/test/scala/com/dearlordylord/bend/idea/architecture/ArchitectureRules.scala) inspects compiled production bytecode, including Scala companions. It checks package ownership, dependency direction, subsystem/feature cycles, feature implementation isolation and forbidden platform/I/O dependencies in policy packages. [ArchitectureTest.scala](src/test/scala/com/dearlordylord/bend/idea/architecture/ArchitectureTest.scala) requires actual production classes and proves the checker rejects deliberately invalid Scala dependencies. Those fixtures are test-only and are not packaged.

Keep the rule graph synchronized with ARCHITECTURE.md when adding packages or changing a boundary. Architecture checks enforce A2/A4 structure; editor/compiler tests and review remain necessary for source semantics, cancellation, freshness and offline execution.

## Sandbox smoke check

Launch `runIde`, open an `example.bend` file, and confirm the Bend file type and icon in both light and dark themes. Confirm the plugin loads without installing the Scala plugin or a Bend/JavaScript toolchain. Close the sandbox when finished. The automated editor fixture separately checks registered file recognition, preserved editor contents and both icon resources; Plugin Verifier checks binary compatibility.

Issue #3 adds a restartable UTF-16 lexer, configurable Bend token colors and the standard hash line-comment action. The editor fixtures exercise highlighting after an edit inside a string, comment toggling including the last line without a newline, and undo through the IntelliJ action. The parser definition currently supplies a token tree; tolerant declaration PSI is owned by issue #5.

Issue #4 adds explicit Bend 2 keyword completion and editable `def`, `type`, `law`, `match`, `do` and `import` snippets. Suggestions are withheld inside comments and string literals, including unfinished strings. The completion fixtures invoke IntelliJ lookup and live-template editing, including a linked law placeholder and the final caret position.
