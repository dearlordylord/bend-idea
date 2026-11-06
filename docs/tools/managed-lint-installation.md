# Managed bend-lint installation

The explicit installer downloads **bend-lint 0.3.0** from npm and verifies the
SHA-512 in `src/main/resources/lint/tool-pins.properties`. It either verifies a
supplied Bun **1.4.4+38afee6c8**, or downloads the pinned Bun archive for Linux,
macOS or Windows on x64/ARM64 and verifies its SHA-256. Other platforms need an
explicit compatible executable. Available packages do not establish editor
compatibility on every platform; validation below states the exercised host.

The linter uses its own **Bend 2.0.39**, separately from the project's configured
compiler. Windows uses the upstream linter's pinned fork
`MattCozendey/bend/d38038df4c456f5c8421f3630787f6de91d6c724`.
Installation verifies the complete downloaded compiler source/effs inventory
against pinned tree digests before executing that compiler. Pinned Hub names are
written before preparation, and the compiler verifies each fetched file against
the package manifest. Installation also verifies complete rule/API packages against
these exact content identities:

- `bend-lint-api@0.3.0.0`: `0x94bdd21968f0b8ad8c1be0e7e2b606a0`
- `bend-lint-rules@0.3.0.0`: `0xee9c895588f324c3d6d34fd0d503daaf`

A tree digest sorts relative forward-slash paths and hashes each path, a NUL,
and the binary SHA-256 of that file, then takes SHA-256 of the sequence. The
npm source digest excludes generated `src/linter/build/`. Hub identity follows
the compiler's published package format: sorted `sha256(bytes) path\n` records,
SHA-256 of their concatenation, first 128 bits, prefixed `0x`.

Each installation has a stable generation directory with the unmodified npm
package, its LICENSE, `cache/`, `packages/`, and the fixed `bend-lint.json`.
Preparation builds JavaScript rules before writing `ready.properties`. PATH,
CC and ProgramFiles point at unavailable tool locations during preparation,
which prevents native compiler discovery and detached builds. The default
rules are `layout/case` and `no_unused/imports`; the upstream prepares its
full bundled rule program once. Project rule configuration is not executed by
this installation.

Only a fully prepared, verified generation is selected. A single atomic
`selection.properties` replacement retains both active and previous generation
names. A failed or canceled replacement removes its incomplete generation and
leaves selection intact. Rollback atomically exchanges two intact generations.
Installation and rollback use the same OS file lock. There is no automatic
update; a future plugin pin update takes effect through explicit installation.

Downloads are HTTPS-only, limited to five redirects, 120 seconds and 5 MiB for
npm/128 MiB for Bun. Extraction rejects escaping paths, links, unsupported entry
types, excessive files and oversized contents. Preparation is a bounded,
cancellable process (240 seconds, 1 MiB combined output). Tool validation caps
source/build inventory at 4,096 files and 64 MiB, separately limiting Bun to
256 MiB. Validation performs disk hashing and belongs on a worker, including
settings status checks; it must not run on the EDT or action update thread.

Only explicit installation accesses the network. Analysis consumes the prepared
inventory and must apply its own offline process guard and snapshot freshness.
The caller must not interpret successful installation as a proof verdict or a
claim that a later lint run covered every declaration.

## Provenance and validation

The Scala installer, API, adapter and fixtures are original work. No upstream
implementation is copied into the plugin. Downloads retain their own licenses;
the fetched npm MIT LICENSE stays with the package. Checksum-verified compiler
Apache-2.0 and Bun license texts are retained in the generation too. The plugin contains pins
and orchestration only, not npm/Bun/compiler payloads. Sources consulted:
[npm 0.3.0 metadata](https://registry.npmjs.org/bend-lint/0.3.0),
[upstream linter](https://github.com/MattCozendey/bend-lint/blob/c6f31c0c2ef4587368dd6a5443a6d42e22e975fc/src/linter/seam.ts),
[Bun 1.4.4 assets](https://github.com/oven-sh/bun/releases/tag/bun-v1.4.4).

`BendLintInstallationTest` covers traversal/link rejection, checksum mismatch,
file preservation, cancellation and unchanged selection on failed rollback.
A direct JDK 21/dotc 3.3.7 compile with the project warning flags passed, and the
first direct JUnit run passed four cases including two actual installations on
Linux ARM64 with pinned Bun 1.4.4 (28.626 s). Later integrity and native-artifact
checks are being rerun before handoff. This is subset verification, not the
project Gradle/IDE gate.

Its explicit network scenario uses `BEND_TEST_INSTALL_BUN` to test actual
installation, verified readiness, replacement, rollback, and rejection of an
altered prior generation. A skipped scenario is not installation evidence.
