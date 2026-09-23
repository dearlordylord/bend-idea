# Bend2

![Bender-inspired striped arms flowing like pipes](design/splash/readme-splash.png)

Language support for [Bend 2](https://github.com/bendlang/bend) in IntelliJ IDEA. The plugin recognizes `.bend` files and provides source editing features without a Bend installation. Compiler diagnostics use a separately configured Bend executable.

Installs on IntelliJ IDEA builds **2025.1 and newer**. Compatibility has been verified with Community 2025.1 and Ultimate 2026.1; verify later releases before relying on them.

## Features

### Editing and completion

- Syntax highlighting with configurable Bend colors, light and dark file icons, and hash line-comment toggling.
- Keyword completion and editable snippets for `def`, `type`, `law`, `match`, `do`, and `import`.
- Source-aware completion for declarations, constructors, parameters, local bindings, match cases, do blocks, and proof binders. Suggestions can show source signatures and nearby comments.
- Completion from the selected Base source and direct imports, including qualified names and unsaved changes in open files. Import paths complete from local files, directories, and cached packages without downloading them.

### Navigation and documentation

- Go to Declaration for resolved local, imported, and Base names, including import aliases and literal dotted names.
- Find Usages and Highlight Usages for resolved source references across the project.
- Quick Documentation for source declarations, signatures, comments, and law/fill relationships. It shows source information, not inferred expression types.

### Compiler checking

- **Check Current Bend File** runs a supported Bend compiler against a snapshot of the current unsaved file and its loaded imports, without running `main` or fetching packages.
- Optional background checking refreshes diagnostics after edits to the root or its dependencies. Results belong to the checked root, and older results are discarded when their inputs change.
- **Bend Check Status** reports checking, current, stale, incomplete, failed, unavailable, and timeout states. Compiler errors appear in the editor when their source location can be mapped unambiguously.

## Getting started

1. Install the plugin in IntelliJ IDEA Community Edition 2025.1 and open a `.bend` file. Editing features work immediately.
2. To use compiler checking or Base completion, search for **Bend** in IDE Settings and select your Bend executable and Base source. The settings also include the package cache and a background diagnostics toggle.
3. Use **Tools → Check Current Bend File** for an immediate check, or enable background diagnostics for checks after edits. Use **Tools → Bend Check Status** to inspect the current result.

Checking requires a Bend executable with documented `--check-only` support. If the executable, Base, or another required capability is unavailable, the plugin reports that state instead of treating the file as successfully checked.

## Planned

- **Editor structure:** brace and quote assistance, folding, structure view, indentation, and conservative formatting.
- **More navigation and refactoring:** workspace symbol search, parameter information, semantic reading aids, and reference-based rename for locals, aliases, declarations, and paired laws.
- **Bend workflows:** proof-root selection, law and hole navigation, proof progress, code skeletons, explicit Run and Build actions, and import/file tools.
- **Compiler-backed assistance:** structured diagnostics, goals and context, actual expression types, expected-type completion, resource feedback, validated proof edits, and explicit normalization. These depend on compatible compiler capabilities.

The [implementation specification](https://github.com/dearlordylord/bend-idea/issues/1) and [feature issues](https://github.com/dearlordylord/bend-idea/issues) track the full roadmap.

## Build

Use a full **JDK 21**. The Gradle wrapper downloads the pinned build tools and IDE dependencies as needed. Distributions must always be signed. Keep `private.pem` and `chain.crt` outside the repository in the directory named by `BEND_IDEA_SIGNING_DIR`.

```sh
./gradlew check                 # editor fixtures and architecture checks
./gradlew verifyPlugin          # check compatibility with the pinned IDEA versions
BEND_IDEA_SIGNING_DIR="$HOME/.config/bend-idea/signing" \
  ./gradlew signPlugin verifyPluginSignature  # build and verify the signed ZIP
./gradlew runIde                # launch an IDE sandbox
```

Install only `build/distributions/*-signed.zip` with **Settings → Plugins → Install Plugin from Disk**. If using the local self-signed certificate, add `chain.crt` under **Settings → Plugins → Manage Plugin Certificates**. `buildPlugin` creates an unsigned intermediate; never distribute it. After signature verification, remove unsigned ZIPs from `build/distributions/`. CI uploads a plugin archive only when the repository secrets `BEND_IDEA_SIGNING_PRIVATE_KEY` and `BEND_IDEA_SIGNING_CERTIFICATE_CHAIN` are configured and signing succeeds. The plugin is implemented in Scala 3; Gradle uses Kotlin build scripts.

For the design and contribution rules, see [ARCHITECTURE.md](ARCHITECTURE.md), [CONTEXT.md](CONTEXT.md), [AGENTS.md](AGENTS.md), and [REVIEWER.md](REVIEWER.md).

## License

[Apache-2.0](LICENSE)
