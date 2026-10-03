# Bend formatter without a system Java installation

Research date: 2026-10-03. Three independent Luna researchers investigated runtime distribution, installation and platform packaging against official sources. This note records the shared conclusion and our chosen implementation.

## Product precedents

- [Coursier setup](https://get-coursier.io/docs/cli-installation) installs a JVM when one is missing and configures user PATH; [its Java command](https://get-coursier.io/docs/cli-java) manages downloads and caching. It adds a bootstrap tool and an initial network dependency.
- [JBang Java versions](https://www.jbang.dev/documentation/jbang/latest/javaversions.html) automatically downloads a suitable JDK. Its [installation](https://www.jbang.dev/documentation/jbang/latest/installation.html) remains a prerequisite.
- [Clojure CLI installation](https://clojure.org/guides/install_clojure) requires an installed Java runtime. It does not meet our zero-Java-setup goal.
- [Elasticsearch installation](https://www.elastic.co/guide/en/elastic-stack/current/installing-elastic-stack.html) bundles OpenJDK in its distributions: a precedent for shipping the runtime with the application.

## Alternatives and decision

[JDK jpackage](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html) can produce self-contained application images with a launcher and runtime. Packaging must happen on the target platform. [jlink](https://docs.oracle.com/en/java/javase/21/tools/jlink.html) creates a custom runtime; its maintainer must update that runtime as Java security releases arrive. Native installers add OS-specific packaging requirements that an archive avoids.

[GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/) removes the JVM at execution time, but introduces AOT analysis and a native build toolchain per platform. Reflection/resources may need explicit configuration. We have not validated the formatter under Native Image, so it is not selected for this delivery.

Chosen: preserve the existing JVM formatter and portable JAR; add OS/architecture archives containing a jlink runtime and a small shell/Windows command launcher. Users extract the archive and invoke `bend-format`. Launching uses the bundled runtime, performs no downloads and does not require system Java, Bun, IntelliJ or the Bend compiler. Do not add a custom runtime downloader or updater. This is packaging at the existing CLI boundary; the shared layout policy stays unchanged.

Static `jdeps` on the released fat JAR reports `java.base,java.compiler,java.sql,jdk.unsupported`. [Oracle migration guidance](https://docs.oracle.com/en/java/javase/21/migrate/preparing-migration.html) warns that static dependency analysis cannot find every reflective use; real extracted-archive smoke tests remain required on each target.

## Distribution constraints

[Temurin licensing FAQ](https://adoptium.net/docs/faq) documents GPLv2 with Classpath Exception. Preserve the runtime legal directory and notices; include the project license and dependency notices. Record the full runtime release information and JAR hash in each archive. Update the pinned Temurin patch when preparing future formatter distributions.

Use native builds for Linux x64/ARM64, macOS Intel/Apple Silicon and Windows x64. [GitHub runner documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) describes the corresponding hosted runners. Linux archives target glibc systems; Alpine/musl and Windows ARM64 are not claimed. macOS archive execution has not been notarized; this delivery does not claim notarization or a signed native executable. The existing signed IntelliJ ZIP is a separate artifact.

A local ARM Linux experiment produced a 43 MiB jlink runtime (24 MiB compressed), compared with a 346 MiB full JDK and a 7.2 MiB formatter JAR. These are observed sizes, not cross-platform guarantees. Build and test the final archives to obtain their actual sizes.

## Acceptance checks

Extract into a path containing spaces. Run with no system Java in PATH and an invalid JAVA_HOME. Verify version metadata, argument and stdin forwarding, current-directory preservation, local EditorConfig, check/fix exit statuses 0/1/2, exact output and idempotence. Publish only archives whose native-platform smoke checks passed; retain the portable JAR for users who already manage Java.

## Implementation validation

The initial local `testFormatDistribution` gate passed on Linux ARM64 using Temurin 21.0.12.1+1 and Python 3.12.13. The JAR SHA-256 remained `8beab941b5a409ef52c814c1ba4eeda5245cc112b433d1e801bf8e19e0b2dac8`, identical to the published 0.1.11 JAR. The compressed archive measured about 30 MiB. Distribution tooling requires Python 3.12+ at build time for safe tar extraction; end users need neither Python nor Java. The packager explicitly permits jdeps references from Scala's quoted compile-time API to erased `scala.AnyKind`; other missing dependencies fail packaging.

The workflow's publication step refuses to overwrite existing release assets. Run with `publish=false` to rebuild/verify an existing release without replacing its files.

The distribution and existing verification workflows use Node 24 action releases. [GitHub retired Node 20 actions on 2026-09-23](https://github.blog/changelog/2026-09-23-node-20-is-no-longer-available-in-github-actions/); upstream action metadata was checked before choosing the replacements. Bun's action remains commit-pinned, independently of the pinned Bun runtime version.

The final 0.1.12 native matrix passed on all five targets in [run 37136283151](https://github.com/dearlordylord/bend-idea/actions/runs/37136283151). Compressed archives range from 28.4 to 32.0 MB (decimal). All publicly downloaded archives matched their release checksum manifest, and each contained the exact published JAR. Windows testing exposed an existing editorconfig-core path-matching requirement; the CLI now passes native separators as forward slashes while preserving literal Unix backslashes. Astra reviewed the correction and README with no remaining findings.

`distribution.json` source revision is the packaging workflow's source revision, not a claim about the downloaded formatter JAR's Git source; `jarSha256` identifies the exact release JAR. The release tag pins its tested product source independently. Adoptium's canonical API SemVer for OpenJDK 21.0.12.1+1-LTS is `21.0.12+101.0.LTS`, which setup-java requires when pinning this patch.

## Package-manager installation

Homebrew is the first package-manager channel for macOS and Linux. The public [tap](https://github.com/dearlordylord/homebrew-tap) provides `brew install dearlordylord/tap/bend-format`; [Homebrew's tap documentation](https://docs.brew.sh/How-to-Create-and-Maintain-a-Tap) describes automatic tap installation with a fully qualified formula name. The formula selects the existing immutable runtime archive for OS and CPU, verifies its SHA-256, retains runtime/legal files under `libexec`, and links the launcher into `bin`. Updates use `brew update` and `brew upgrade bend-format`.

An npm channel remains optional: [npm package metadata](https://docs.npmjs.com/cli/configuring-npm/package-json/) supports executable mappings and OS/CPU selection, but that delivery would require Node/npm and another wrapper/package publication lifecycle. Homebrew is sufficient for the initial macOS/Linux installation flow. Windows retains the ready-to-run release archive. Neither channel changes formatter logic.

Astra reviewed the tap formula and both installation READMEs with no remaining findings. Native installation evidence is recorded in MARKETPLACE.md.
