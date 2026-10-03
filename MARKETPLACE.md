# Marketplace releases

Bend2's plugin ID is `com.dearlordylord.bend.idea`; its Marketplace listing name is `Bend2`. Build with JDK 21. The plugin targets IntelliJ IDEA 2025.1 and declares no upper IDE build limit. Verify newer IDE releases before claiming support.

The public [Bend2 Marketplace listing](https://plugins.jetbrains.com/plugin/34452-bend2) serves version 0.1.9 as of the public API check on 2026-10-03. The authenticated vendor page last checked on 2026-10-02 marked 0.1.9 **Approved** and 0.1.10 **Under review**. The public plugin details API reports the **Plugin Site** URL as `https://bend-idea.dearlordylord.com/`.

## 0.1.2 submission

The 0.1.2 release candidate contains the work merged since 0.1.1:

- Add a Bend structure view, source-preserving formatting, and binding-aware rename.
- Add workspace declaration search through Go to Symbol.
- Harden checking with immutable state transitions, conservative CLI verdicts, and exact source mapping for imported compiler diagnostics.
- Make real compiler evidence reproducible with pinned Bend and Bun inputs, offline/check-only fixtures, and CI provenance reporting; enforce Scala formatting, selected compiler warnings, and scoped policy mutation checks.

The clean JDK 21.0.10 release gates passed on 2026-09-24: 270 tests, 0 failures, 0 errors, and 0 skipped; packaging, structure, project configuration, and Plugin Verifier tasks also passed. Plugin Verifier reported compatibility with Community 2025.1 and Ultimate 2026.1, alongside deprecated, scheduled-for-removal, experimental, and internal API usage notices. `signPlugin` and `verifyPluginSignature` passed using the maintainer signing identity stored in the iCloud Drive folder `bend-idea-signing`.

The signed artifact is `build/distributions/bend-idea-0.1.2-signed.zip` (SHA-256: `815c6cc43de4274e86a9d46ca515932bfd072de6dc0bf18389a3d24d476d8d69`). It was uploaded on 2026-09-24 through the authenticated JetBrains Marketplace vendor page to the Stable channel as update **1178294**. The portal reported no problems found so far and showed the update as **Under review**; final support review may take up to two business days. This is a successful submission, not public approval or publication.

The vendor page also showed versions 0.1.0 and 0.1.1 as **Under review** on that date. A public details API query returned an empty listing while these versions were pending; that result did not mean the plugin listing was missing. Check the authenticated vendor page for current approval state before describing the plugin as publicly available.

## 0.1.3 rollback submission

This package restores the source baseline from commit `2df22df`, bumps the version, and uses the Marketplace listing name `Bend2`. Its signed archive is in the rollback worktree at `build/distributions/bend-idea-0.1.3-signed.zip` (SHA-256: `80c25586c0e909b39c78521fc377b0f995212ed5dd78935e6bf665130ce12fdc`). Marketplace accepted it as Stable update **1178310** on 2026-09-24. It remains under review and is not public. Its verifier report has two Internal API usages in `BendSettingsConfigurable`.

## 0.1.4 fixed submission

The fix is pushed as `codex/fix-internal-api` at commit `6e91d38`. The file element type is now a single instance of a concrete class, so Scala no longer emits a top-level object wrapper that calls IntelliJ's Internal API `IElementType.getDebugName()`. The current source also uses `BaseConfigurable` for settings. Plugin Verifier reports no Internal API usages and compatibility with Community 2025.1 and Ultimate 2026.1.

The signed archive is `build/distributions/bend-idea-0.1.4-signed.zip` (SHA-256: `cae3f67755d5c7780c315472ed269e8cb6ada1111eaacb96f3b9a75beb44f5e4`). On 2026-09-24, JDK 21.0.10 release checks passed: 270 tests, 0 failures, 0 errors, 0 skipped; architecture, packaging, project configuration, Plugin Verifier, and signature verification passed. Marketplace accepted it as Stable update **1178547** on 2026-09-24. It is **Under review** while Support conducts additional checks and is not public yet.

## 0.1.7 submission

The signed 0.1.7 archive is available in [GitHub Release v0.1.7](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.7) with SHA-256 `0b29a81e43aedf8cc46338fed4aebf3cf1aed29dc4baf40a6cc7843f80c5b625`. Its public download matched the locally signed file. The certificate fingerprint remains `87:66:5D:BD:29:D6:BC:8F:E2:5F:AF:62:9D:6E:4D:76:5B:9C:DC:3E:3B:B1:76:53:EE:C6:D2:1F:C7:1B:71:C2`.

The clean JDK 21 release gate passed on 2026-09-27: 441 tests, zero failures or errors, one optional current-compiler smoke test skipped; packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing, and signature verification passed. JetBrains Marketplace accepted the same signed ZIP in the Stable channel as update **1181339**. The vendor page showed **Under review**; public Marketplace availability awaited JetBrains approval. The custom repository feed advertised 0.1.7 independently of that review.

## 0.1.8 release and Marketplace submission

The signed 0.1.8 archive is available in [GitHub Release v0.1.8](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.8) with SHA-256 `0bdc4511293290c196c61926beb0cff7d035eadc8a4edee5d9b649ea86746dbd`. The release asset was downloaded and matched the locally signed ZIP. The public signing certificate is attached. The custom plugin feed advertises 0.1.8 after the release asset became available.

The JDK 21 release gate passed on 2026-09-28: 459 tests, zero failures or errors, one optional current-compiler smoke test skipped; packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing, and signature verification passed. The Community verifier reports six internal API usages in the existing proof progress tool-window factory; the verifier still classifies the plugin as compatible. The signed 0.1.8 ZIP was submitted through Gradle to JetBrains Marketplace's default Stable channel as update **1181912**. The authenticated Marketplace vendor page showed **Approved** on 2026-09-29; the public updates API also lists 0.1.8.

## 0.1.9 release and Marketplace submission

The signed 0.1.9 archive is available in [GitHub Release v0.1.9](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.9) with SHA-256 `0c86be3c85a33b6a06e4e4f08dc5467e057c8bf69bf7d278cb26d0e474aaaf2e`. A public download matched the locally signed ZIP. The public signing certificate is attached. Both the primary and fallback custom plugin feed URLs advertise 0.1.9, with metadata matching the signed plugin's `plugin.xml`.

The clean JDK 21 release gate passed on 2026-09-29: 461 tests, zero failures or errors, one optional current-compiler smoke test skipped; architecture, packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing, and signature verification passed. Implementer self-review under `REVIEWER.md` found no actionable issue against #27 and the applicable architecture rules. The same signed ZIP was uploaded through the authenticated JetBrains vendor page to the Stable channel as update **1182848**. Marketplace found no initial problems, but the vendor page shows **Under review**; 0.1.9 is not yet publicly available through Marketplace.

## 0.1.10 release and Marketplace submission

The signed 0.1.10 archive is available in [GitHub Release v0.1.10](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.10), tagged at the tested release commit `ea41f7a`, with SHA-256 `f97eb51bc217e19851bf18d77b2e921ed14726640b27559e43a7c5e2c8146a0f`. An unauthenticated public download matched the locally signed ZIP. The public signing certificate is attached; its SHA-256 fingerprint is unchanged from 0.1.9.

The clean JDK 21.0.12.1 release gate passed on 2026-10-02: 475 tests, zero failures or errors, one optional current-compiler smoke test skipped; architecture, packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing and signature verification passed. Verifier reports compatibility with the existing API usage notices, including six internal API usages in the Community report. Implementer self-review under `REVIEWER.md` found no actionable findings for #27 and rules A2, A3, A7 and A8. The parameter-hint fix was also confirmed in the user's IDEA session.

JetBrains Marketplace accepted the same signed ZIP through the authenticated vendor page in Stable as update **1186157**. The page showed **Under review**, with no initial problems found; public Marketplace availability awaits JetBrains approval. Cloudflare Pages deployed the custom feed from `master`; both `https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml` were checked to advertise 0.1.10 with metadata matching the signed plugin, after its release asset was verified.

## 0.1.11 GitHub and custom repository release

[GitHub Release v0.1.11](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.11) is tagged at tested candidate `ae7af4010ff6690f2d240374c0f661b6d79575f3`. It includes the signed plugin ZIP, standalone formatter/style-checker JAR, public signing certificate and SHA256SUMS.txt. Unauthenticated downloads matched the local artifacts, and the downloaded JAR reports `bend-format-tool 0.1.11`.

- Signed ZIP SHA-256: `55dc968dceec5ab45a98dc02ca6b5db952457a784a6439f982c3ee15336b892d`.
- Standalone JAR SHA-256: `8beab941b5a409ef52c814c1ba4eeda5245cc112b433d1e801bf8e19e0b2dac8`.

The clean JDK 21.0.12.1 release gate passed on 2026-10-03: 526 tests passed, zero failures or errors, and one optional current-compiler smoke test skipped. Architecture, packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing and signature verification passed. Verifier classified both IDE targets as compatible with existing API usage notices. Independent Astra Standards and Spec reviews found no outstanding actionable findings; the user confirmed reformatting and configured constructor highlighting in IDEA.

Both the primary and fallback custom feed URLs were verified publicly on 2026-10-03 to advertise 0.1.11. Their metadata matches the signed archive's packaged plugin.xml; the feed was published after public asset verification. This release uses GitHub and the custom repository by maintainer choice; 0.1.11 was not submitted to JetBrains Marketplace.

## 0.1.12 formatter distributions and custom repository release

[GitHub Release v0.1.12](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.12) is tagged at tested product candidate `8496325ecc8b167da380d5804ca1d05a2c962589`. It contains the signed plugin, portable formatter JAR and five ready-to-run runtime archives: Linux x64/ARM64, macOS Intel/Apple Silicon and Windows x64. All five native-platform smoke tests passed in [workflow run 37136283151](https://github.com/dearlordylord/bend-idea/actions/runs/37136283151), including the Windows EditorConfig regression. Public downloads of all eight payload assets matched their checksum manifests. The standalone archives contain the exact release JAR and Temurin 21.0.12.1+1; the metadata source revision identifies the packaging workflow source, while the JAR hash identifies formatter bytes.

- Signed ZIP SHA-256: `b2bdeda965cc95a46713593633c53fbda43fada9becb28f6ff10e6a75a873b30`.
- Portable JAR SHA-256: `7a95698368650502b6dba55674a451ec90d9ba2bbc3a2b6f6fdaafa3cb160c09`.
- Runtime archive checksums: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.12/BEND-FORMAT-SHA256SUMS.txt).

The clean JDK 21 release gate passed on 2026-10-03: 527 tests passed, zero failures/errors, one optional current-compiler smoke skipped; both architecture tests, quality checks, packaging, structure, project configuration, Plugin Verifier for IC 2025.1 and IU 2026.1, signing and signature verification passed. The local runtime archive smoke passed as well. Existing verifier API notices remain. Independent Astra reviewed the adapter correction and the user instructions with no outstanding actionable findings. The shared formatting policy is unchanged from 0.1.11.

Both primary and fallback custom feed URLs were verified publicly on 2026-10-03 to advertise 0.1.12, with metadata matching the signed plugin, after public asset verification. This release uses GitHub and the custom repository and was not submitted to JetBrains Marketplace.

## 0.1.13 formatter coverage and custom repository release

[GitHub Release v0.1.13](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.13) is tagged at tested source commit `97cc8f006fdf8ec0c371b610a98cf7f02f1f677d`. It contains the signed IntelliJ plugin, portable formatter JAR and all five bundled-runtime archives. The formatter now recognizes tuples, list literals, calls containing multiline lists and indented result-arrow continuations. The shared IDE/CLI policy retains its conservative checks and wrapping intent.

- Signed ZIP SHA-256: `64d1dace02b99f88e8798c6c2a6b6855a9096f3ae9845ca26fa25160a90e64d4`.
- Portable JAR SHA-256: `6d2aa3391e49669b57ec29d5867705a9073b9552e847bb87a84daa46de95dbf3`.
- Runtime archive checksums: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.13/BEND-FORMAT-SHA256SUMS.txt).

The clean JDK 21 release gate passed on 2026-10-03: 530 tests passed, zero failures/errors, one optional installed-compiler smoke skipped; architecture and quality gates, packaging, structure, project configuration, Plugin Verifier for Community 2025.1 and Ultimate 2026.1, signing and signature verification passed. Existing verifier API notices remain. Implementation self-review followed `REVIEWER.md`; no independent review is claimed.

The released JAR safely formatted all 87 tracked Bend files at D&D commit `889f46839c16560b21ec3abf10e9f2a6d79c9eb4`, with zero unavailable files and repeat-fix idempotence. With Bend 2.0.34, the original and formatted copies both reported `ALL PROOFS CHECK`, battle/app/relentless-endurance demo stdout matched, and the benchmark check passed. This bounded sample does not advance the plugin's approved compiler pin. Production D&D sources and formatter pins were not changed by this release.

All five runtime packaging/smoke jobs and asset publication passed in [run 37146121527](https://github.com/dearlordylord/bend-idea/actions/runs/37146121527). Anonymous downloads of every payload matched both manifests and the locally verified signed ZIP/JAR/certificate. The existing signing identity is reused. The published Linux ARM64 archive also passed the new tuple/list/continued-header CLI fixture locally without system Java.

Homebrew tap commit `cca665eabd7542a4df3517d9cf6a98a8de9abcdd` advances all four macOS/Linux archives to 0.1.13. [Install and test run 37146422995](https://github.com/dearlordylord/homebrew-tap/actions/runs/37146422995) passed style/audit, actual installation, version and formatting regression tests, and removal on all four native targets. Local Homebrew was unavailable; the native tap CI supplies installation evidence.

On 2026-10-03, both the primary custom repository and its Cloudflare Pages fallback served 0.1.13 with metadata matching the signed plugin. The signed ZIP was publicly downloaded and its checksum verified. An interactive IDE installation/update was not exercised. GitHub Release v0.1.13 is published as the stable latest release. The signed ZIP is prepared for IntelliJ distribution; Marketplace submission and approval remain separate. The combined maintainer workflow is documented in [docs/releasing.md](docs/releasing.md).

## 0.1.14 release

[GitHub Release v0.1.14](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.14) is tagged at tested source commit `df87e66441f3927e1f6e3bd5960b17a6e71d993a`. The CLI now identifies itself as `bend-format`, provides top-level and subcommand help, explains argument/file errors, and supports `--` for dash-prefixed filenames. Repository-wide checks work from subdirectories. The shared formatter policy, compiler pin and architecture boundaries remain unchanged (A1–A4/A9); filesystem and argument handling stay in the CLI/script owners.

- Signed plugin SHA-256: `01d77cec8ba5549c233d0e1957755d04604b5a49b3c85f4a1a8c303ff3c8a0f3`.
- Portable JAR SHA-256: `b848c176dce8cc25000b8b6bab47db2007e229f42757b5147638a8efa571b933`.
- Runtime archive checksums: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.14/BEND-FORMAT-SHA256SUMS.txt).

The clean JDK 21 release gate passed on 2026-10-03: 533 tests passed, zero failures/errors, one optional installed-compiler smoke skipped; both architecture tests, quality checks, packaging, structure, project configuration, signing and signature verification passed. Plugin Verifier reports compatibility with Community 2025.1 and Ultimate 2026.1, with the existing API usage notices. Independent UX and DX reviews covered the CLI/integrations; implementation and release self-review followed `REVIEWER.md` and found no remaining actionable findings.

All five native archive/smoke jobs and publication passed in [run 37150609036](https://github.com/dearlordylord/bend-idea/actions/runs/37150609036). Anonymous downloads of every payload matched both manifests and the locally signed ZIP/JAR/public certificate. The existing signing identity is reused. The published Linux ARM64 archive and the consumer CI check body were also exercised locally with hostile `JAVA_HOME`, tracked spaced/dash filenames, untracked exclusion, read-only behavior and exit statuses 0/1. The [consumer CI example](docs/examples/bend-format-user-ci.yml) pins the verified 0.1.14 Linux x64 archive and digest.

Homebrew tap commit `302b003676de67b2c6ab4df853f4c0f525c53e16` updates all four macOS/Linux archive URLs/checksums and tests the renamed version output, help, argument errors and dash-prefixed files. [Install and test run 37150752894](https://github.com/dearlordylord/homebrew-tap/actions/runs/37150752894) passed style/audit, actual installation, command/formatter tests and uninstall on all four targets. Local Homebrew was unavailable; native tap CI provides installation evidence.

On 2026-10-03, both the primary custom feed and its Cloudflare Pages fallback served 0.1.14 with metadata matching the signed plugin. Its anonymous ZIP download matched the locally verified signed artifact. GitHub Release v0.1.14 is published as the stable latest release. An interactive new-version IDE installation/update was not exercised. The signed ZIP is published for the custom repository; Marketplace submission/approval remains separate. See [docs/releasing.md](docs/releasing.md).

## Homebrew distribution

The initial public [Homebrew tap](https://github.com/dearlordylord/homebrew-tap) installed the 0.1.12 formatter archives with `brew install dearlordylord/tap/bend-format`. The formula retains the complete bundled runtime and notices. [Native installation run 37138435794](https://github.com/dearlordylord/homebrew-tap/actions/runs/37138435794) passed on all four targets on 2026-10-03, covering formula style/audit, installation, the installed command's version and EditorConfig check/fix behavior, exit statuses and removal on macOS Intel/Apple Silicon and Linux x64/ARM64. This distribution adds no plugin or formatter code changes.

## Future updates

After publishing and verifying future formatter archives, update the four versioned URLs and SHA-256 values in [Formula/bend-format.rb](https://github.com/dearlordylord/homebrew-tap/blob/main/Formula/bend-format.rb). Keep all platform versions aligned and require the tap's native installation checks to pass before advertising the update.

The verified 0.1.4 signed archive is also available in the public [GitHub Release v0.1.4](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.4), tagged at tested candidate commit `6e91d38c3c6194b42cffe651594bf587a1748212`. The release asset was downloaded without authentication and matched the SHA-256 above. A public copy of the self-signed certificate is attached for users who choose to trust it in IDEA. The GitHub release does not establish Marketplace approval.

The [custom plugin repository feed](https://idea.dearlordylord.com/updatePlugins.xml) is served by the Git-connected Cloudflare Pages project `bend-idea-plugins` from [`docs/updatePlugins.xml`](docs/updatePlugins.xml). The domain's Gandi DNS has a CNAME from `idea` to `bend-idea-plugins.pages.dev`; Cloudflare reports both ownership and HTTPS validation active. If the new hostname is not resolving for a user yet, the [Cloudflare Pages project URL](https://bend-idea-plugins.pages.dev/updatePlugins.xml) serves the same feed. For each future pre-Marketplace release, verify that the signed ZIP is publicly downloadable first, then update the feed's `version`, `url`, `idea-version`, `name`, `description`, and `change-notes` to match the ZIP's packaged `plugin.xml`. Publish the feed change only after the release asset is live. Cloudflare Pages deploys `docs/` from `master` automatically; the feed metadata is maintained manually, with no automatic release-to-feed workflow.

The 0.1.6 signed ZIP is public in [GitHub Release v0.1.6](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.6). At that release, the custom feed advertised 0.1.6 and linked to its signed ZIP; the public certificate and release page were linked from the repository landing page. The public download matched SHA-256 `0f686859619af5e43bf0a5519b5caf232c8de0f7675c839d91562b27b010ec62`.

Increment `pluginVersion` in `gradle.properties` and update the `<change-notes>` section in `src/main/resources/META-INF/plugin.xml`. Keep the open-ended IDE range and verify newer IDE releases before claiming support.

Use a full JDK 21 and run the release gates:

```sh
./gradlew --no-daemon clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration signPlugin verifyPluginSignature
```

The real compiler checks require the pinned Bend checkout and Bun executable described in [`ci/bend-test-toolchain.properties`](ci/bend-test-toolchain.properties). Signing uses `BEND_IDEA_SIGNING_DIR` pointing to the directory containing `private.pem` and `chain.crt`. Keep those files and any Marketplace token out of Git and command history. Inspect the signed archive, then upload it through the authenticated Marketplace listing or use `publishPlugin` with `PUBLISH_TOKEN`. Confirm the channel and version before uploading. Check Marketplace for the resulting update and approval state; a successful upload is not proof of publication.

The CI workflow never publishes to Marketplace. If signing secrets are configured, its signed artifact is for retrieval and verification, not evidence of Marketplace acceptance.
