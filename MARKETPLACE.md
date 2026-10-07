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

## 0.1.15 release

[GitHub Release v0.1.15](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.15) is tagged at tested source commit `83b019d4f57cb3f1acfc76058e74edbc01ec5fea`. Long equality/inequality propositions now wrap across laws, binders and result types, including nested calls, constructors and parenthesized conjunctions. The shared pure syntax policy owns the layout; IDE and CLI consume it without a new API or compiler pin (A1–A4/A9).

Local release gates passed with JDK 21: 540 tests passed, one optional current-compiler smoke test skipped, and both architecture tests passed. Plugin structure/configuration, signing and signature verification passed. Plugin Verifier accepted IC 2025.1 and IU 2026.1 with the documented API-use notices. The local bundled Linux ARM64 distribution passed smoke checks with Python 3.12 after the default Python 3.11 was rejected by the packaging prerequisite. Self-review under REVIEWER.md found no actionable findings; this is not an independent review. Real pinned compiler fixtures preserve bindings/references and the PR #82 before/after parser comparison preserves all 3,813 declarations; this comparison is not a complete proof recheck.

All five native archive/smoke jobs and publication passed in [run 37163438675](https://github.com/dearlordylord/bend-idea/actions/runs/37163438675). Anonymous downloads verified all eight payloads against [SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.15/SHA256SUMS.txt) and [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.15/BEND-FORMAT-SHA256SUMS.txt), including agreement with the locally verified ZIP/JAR/public certificate. The signed ZIP SHA-256 is `9f70f29768d57d9aae06efe3a67ef5ac58b9b66e035e2902d03284d3d5ecab7d`; the JAR SHA-256 is `2b41298cd52328248c1352fb8b5dc6c7d26e79c2910f1e3f1b6101b32ea24e68`. The existing signing identity is reused. The consumer CI example pins the verified 0.1.15 Linux x64 archive and digest.

Homebrew tap commit `4029f66da5ac302872a69e695dfb1ed0617a9b83` updates all four archive URLs/checksums and adds an equality regression test. [Install and test run 37163810860](https://github.com/dearlordylord/homebrew-tap/actions/runs/37163810860) passed style/audit, actual installation, command/formatter tests and uninstall on macOS ARM64/x64 and Linux ARM64/x64. An earlier test scenario failed because Homebrew Pathname.write refuses to overwrite an existing EditorConfig; the final commit explicitly removes the test configuration before replacing it. Local Homebrew was unavailable; native CI supplies installation evidence. [Source CI run 37163423439](https://github.com/dearlordylord/bend-idea/actions/runs/37163423439) also passed for the tagged source.

On 2026-10-04 UTC, both `https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml` served 0.1.15 with ID, version, IDE range, name, description and change notes matching the signed plugin. The feed-linked anonymous ZIP download matched the SHA-256 above. GitHub Release v0.1.15 is stable and latest. An interactive new-version IDE installation/update has not been exercised. Marketplace submission/approval is separate from this personal repository release.

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

## 0.1.16 rename fix and IDEA 2026.2 compatibility

[GitHub Release v0.1.16](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.16) is tagged at verified source commit `ae8e61884420cf2174a1452f3b7de7cf35e15fad`. Declaration rename now discovers Bend sources through the file index, preserves current editor text and reports an incomplete inventory or related read-only law before edits. The structure view uses the supported tree-element interface on IDEA 2026.2.

- Signed ZIP SHA-256: `ecef8558d21f4b8f541f7d89dd08f6cf65bfe1da8e4c9d08f3491533ee717055`.
- Portable JAR SHA-256: `7eca4a763d60bb4d38410285ae383f35de0a2f21a5a6f9b18de627edead49070`.
- Runtime archive checksums: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.16/BEND-FORMAT-SHA256SUMS.txt).

The clean JDK 21.0.10 release gate passed on 2026-10-03: 543 tests, zero failures/errors, one optional current-compiler smoke skipped. Real integration used pinned Bend `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` and Bun 1.4.2. Architecture, quality, packaging, structure/configuration, signing and signature checks passed. Plugin Verifier classified IC 2025.1, IU 2026.1 and installed IU 2026.2.3 (`262.10968.63`) as compatible; existing deprecated, scheduled-for-removal, experimental and internal API notices remain. Implementer self-review under `REVIEWER.md` found no outstanding actionable findings for rename/outline behavior and applicable A2, A3, A8 and A9 boundaries. No independent review is claimed.

All five native formatter packaging/smoke jobs and publishing passed in [workflow run 37170008573](https://github.com/dearlordylord/bend-idea/actions/runs/37170008573). Anonymous public downloads of all eight payloads matched their manifests and the local signed ZIP/JAR/certificate. The existing signing identity is unchanged. The custom feed and documentation now target these verified artifacts. Marketplace submission and live feed/Homebrew verification are recorded below once confirmed.

Cloudflare Pages deployed feed commit `619aa81dccf4334587501e876de2707b90f61d9b`. Both `https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml` publicly advertised 0.1.16 with metadata identical to the signed plugin. An anonymous download of the feed-linked signed ZIP again matched the recorded SHA-256. No new-version plugin install in the running IDE is claimed.

Homebrew tap commit `7930a110d933158d44171647cc3f1620dd9d730c` updates all four macOS/Linux URLs and archive hashes. All four actual installation, style/audit, formula test and uninstall jobs passed in [workflow run 37170118770](https://github.com/dearlordylord/homebrew-tap/actions/runs/37170118770). Local `brew update`, `brew upgrade bend-format` from 0.1.14 to 0.1.16 and `brew test bend-format` passed on macOS ARM64. After archive, feed and Homebrew verification, GitHub Release v0.1.16 was marked stable/latest.

Marketplace upload is not yet confirmed. The authenticated upload form was opened with Stable selected, but Chrome's active page changed repeatedly during the native file-selection flow. No successful submission/update ID for 0.1.16 was observed. The maintainer was asked for a short uninterrupted browser window; the signed ZIP remains ready for that final distribution step.


## 0.1.17 release

[GitHub Release v0.1.17](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.17) is tagged at verified source commit `9ff669b`. The versioned changelog and packaged change notes cover explicit normalized-value inlays, offline syntax help, native binding/selection fixes and compiler compatibility improvements. Release preparation changes version metadata only; existing architecture boundaries are unchanged. Implementer self-review followed `REVIEWER.md`, checked the metadata against the signed artifact and found no actionable findings. No independent review is claimed.

Clean JDK 21.0.10 `clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration signPlugin verifyPluginSignature buildFormatTool` passed: 583 tests passed, zero failures/errors, one optional current-release smoke skipped, and two architecture tests passed. Real subprocess inputs were pinned Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c` and Bun 1.4.2. Plugin Verifier accepted IC 2025.1 and IU 2026.1 with existing API notices. The signed plugin's ID, version, since-build=251, description and change notes match the intended release. Signing and signature verification passed with the existing certificate identity.

- Signed ZIP SHA-256: `8115b437f8505e50230b0de8a08eb1996e744403978d6c3f677243cc517392eb`.
- Portable JAR SHA-256: `d71598c7d099f511580c2b5917ab5d6ad50fe4a035a7c2841b5a9177eafbbe3b`.
- Runtime archive checksums: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.17/BEND-FORMAT-SHA256SUMS.txt).

All five native packaging/smoke jobs and publication passed in [workflow run 37216046072](https://github.com/dearlordylord/bend-idea/actions/runs/37216046072). Anonymous downloads of all eight payloads matched their two manifests; plugin/JAR/public certificate also matched the local verified artifacts. Homebrew tap commit `71dfcf6` updates all four macOS/Linux archives using those published hashes. Feed and installation validation are recorded below after confirmation.

Marketplace submission of 0.1.17 is confirmed as Stable update [1187413](https://plugins.jetbrains.com/plugin/34452-bend2/edit/versions/stable/1187413), with status Under review on 2026-10-04. After the maintainer completed the native file-selection/upload step, the authenticated page displayed Upload Successful and the new version with the correct six release-note items. Submission is not approval or public Marketplace availability. The previous 0.1.16 update `1186896` also exists with status Under review; no duplicate 0.1.16 upload was attempted. An interactive 0.1.17 IDE installation has not been exercised.


Cloudflare Pages deployed feed commit `1f4a4e2`. Both the primary custom feed and its Pages fallback publicly served 0.1.17 with ID, since-build, description and change notes matching the signed plugin. Anonymous downloads from both feed links matched the recorded ZIP hash. Local macOS ARM64 `brew update`, upgrade from 0.1.16 to 0.1.17 and `brew test bend-format` passed.

All four native Homebrew style/audit, installation, formula test and uninstall jobs passed in [run 37216403455](https://github.com/dearlordylord/homebrew-tap/actions/runs/37216403455). After archive, feed and installation verification, GitHub Release v0.1.17 was marked stable/latest. Marketplace approval remains pending.


## 0.1.18 release

[GitHub Release v0.1.18](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.18) is tagged at verified source commit `b0af3e4eb8b020046c70ecf0551a9a0718b703d2`. Canonical imports/root namespaces, offline named Hub cache mappings and Go to Type Declaration are recorded in the versioned changelog and signed plugin notes. Release preparation changes shared version/distribution metadata and corrects a stale architecture introduction; it introduces no new runtime contract or formatter policy. Implementer self-review under `REVIEWER.md` checked source/version/feed ownership, packaged metadata, distribution notices and evidence; no actionable findings remain. This is not an independent review.

Clean JDK 21.0.10 release gates passed: 597 main-suite cases, 596 executed successfully, one optional current-release compiler smoke skipped, zero failures/errors, plus two architecture tests passed. Quality checks, plugin packaging, structure/configuration, signing/signature verification and formatter JAR build passed. Real subprocess fixtures used pinned Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c` and Bun 1.4.2. Plugin Verifier accepted IC 2025.1 and IU 2026.1 with existing API notices. The signed metadata uses ID `com.dearlordylord.bend.idea`, version 0.1.18 and since-build 251 without an upper bound.

- Signed ZIP SHA-256: `56e079c23a15c6eb650ccd3aec0c648dc2f9f49bd2a6cee2449649875fe4a9bc`.
- Portable JAR SHA-256: `d6fc0f5ac2fc75452f1e8b942e3ad5b2c4f28f2973d6430c4029786aa69575c4`.
- Public certificate SHA-256: `4ee352701ce7e03a2c5a78af1f4ccf4d351b03e5ccdaf512a625314df3cf88cc`; signing identity unchanged.
- Runtime hashes: [BEND-FORMAT-SHA256SUMS.txt](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.18/BEND-FORMAT-SHA256SUMS.txt).

All five native runtime packaging/smoke jobs and publication passed in [run 37221953059](https://github.com/dearlordylord/bend-idea/actions/runs/37221953059). Anonymous downloads of all eight payloads matched both manifests; the signed ZIP/JAR/public certificate also matched local verified artifacts. Homebrew tap commit `bc1e7db42cad4d0133c9a548b0fbcd21d3640dff` updates all four macOS/Linux archive URLs and hashes. Live feed and installation checks are recorded after confirmation.

The maintainer completed Marketplace upload; authenticated UI displayed Upload Successful and Stable update [1187430](https://plugins.jetbrains.com/plugin/34452-bend2/edit/versions/stable/1187430) for 0.1.18, status Under review on 2026-10-04. Submission is not approval or public Marketplace availability. An interactive new-version IDE installation/update has not been exercised.


Cloudflare Pages deployed feed commit `e959484a8c2921ad97748fb9dea07d7733829d8f`. Both ordinary public URLs (`https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml`) serve 0.1.18; ID, IDE range, name, description and change notes match the signed plugin. The feed-linked ZIP was downloaded anonymously again and matched its recorded SHA-256. Initial cached responses still served 0.1.17; subsequent ordinary requests confirmed the new version at both endpoints.

All four Homebrew native style/audit, actual installation, version/formula-test and uninstall jobs passed in [run 37222206009](https://github.com/dearlordylord/homebrew-tap/actions/runs/37222206009). Local macOS ARM64 `brew update`, upgrade from 0.1.17 to 0.1.18, `brew test bend-format` and installed command version check passed. After archive, feed and installation verification, GitHub Release v0.1.18 was marked stable/latest. Marketplace approval and interactive IDE installation remain unconfirmed; the signed artifact and custom feed are available now.


## 0.1.19 release

[GitHub Release v0.1.19](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.19) is tagged at clean verified source commit `c0b9038ecfc9c6baa4f253a6cfbc8240b931129a`. Quick Documentation Declaration and Implementation links now use IntelliJ native PSI navigation. Two platform-dispatch fixtures cover local Declaration and imported law Declaration/Implementation links. No shared contract or formatter policy changed; architecture rules A2/A3/A8 are preserved. Implementer self-review under `REVIEWER.md` found no actionable findings; no independent review is claimed.

Clean JDK 21.0.10 release gates passed: 599 main-suite cases, 598 executed successfully, one optional compiler smoke skipped, zero failures/errors, and two architecture tests passed. Quality checks, packaging, structure/configuration, signing/signature verification and formatter JAR build passed. Real subprocess fixtures used pinned Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c` and Bun 1.4.2. Plugin Verifier accepted IC 2025.1 and IU 2026.1 with existing API notices. Signed metadata uses the existing ID, version 0.1.19, since-build 251 without an upper bound and the versioned changelog notes.

- Signed ZIP SHA-256: `57db0fe14d0a98f8401ee378f31f8643f7f873bb5712a2d661367b0b9ddc9d12`.
- Portable JAR SHA-256: `50df827b59cb5bca51e2d555e81a2e2bbd8d28d7b799566d3e16653eaac50356`.
- Public certificate SHA-256: `4ee352701ce7e03a2c5a78af1f4ccf4d351b03e5ccdaf512a625314df3cf88cc`; signing identity unchanged.

All five native archive packaging/smoke jobs and publication passed in [run 37225334172](https://github.com/dearlordylord/bend-idea/actions/runs/37225334172). All ten assets were downloaded anonymously; eight payload hashes match both manifests and the initial three match local verified artifacts. Tap commit `11bbb69` updates all four macOS/Linux archive URLs and hashes. Live feed and Homebrew installation evidence will be recorded after confirmation.

Marketplace submission is unconfirmed: this conversation's Computer Use transport returns `Transport closed`, and no publishing token is configured. The signed ZIP is ready; the maintainer was given Upload Update / Stable instructions. No submission, approval or interactive 0.1.19 IDE installation is claimed.


Cloudflare Pages deployed feed commit `dcf2a11`. Both ordinary public feed URLs served 0.1.19 with ID, name, IDE range, description and change notes identical to the signed plugin. Anonymous downloads through both feed links matched the recorded ZIP hash. All four actual Homebrew native style/audit, installation, formula-test and uninstall jobs passed in [run 37225517725](https://github.com/dearlordylord/homebrew-tap/actions/runs/37225517725). Local macOS ARM64 `brew update`, upgrade from 0.1.18 to 0.1.19, `brew test bend-format` and installed version check passed. After these checks, GitHub Release v0.1.19 was marked stable/latest; its public latest endpoint confirms version 0.1.19 and ten assets. Self-review of feed and installation documentation found no findings. Marketplace submission and interactive IDE installation remain unconfirmed.


After restarting Codex, Computer Use reconnected. The maintainer completed the upload; authenticated Marketplace UI displayed Upload Successful and Stable version 0.1.19 as update [1187439](https://plugins.jetbrains.com/plugin/34452-bend2/edit/versions/stable/1187439). The success notice says additional support checks are required before publication. Submission is confirmed; approval and public availability are not. The version detail status was not separately inspected before the maintainer closed the tab. This supersedes the transport blocker recorded above.


## 0.1.20 custom repository release

[GitHub Release v0.1.20](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.20) is tagged at verified release commit `25824c9777fd05b96f149b975ce4ad5b8e63c054`. It includes bounded, validated law/fill navigation caching and source navigation from Quick Documentation links. Versioned CHANGELOG and packaged plugin change notes cover both changes. The user requested distribution through the existing custom plugin repository only; 0.1.20 was not submitted to JetBrains Marketplace. Native formatter archives and Homebrew remain on 0.1.19; the attached portable JAR carries the shared 0.1.20 version with unchanged formatter policy. The GitHub release remains non-latest to preserve the complete 0.1.19 formatter release.

Clean JDK 21.0.12.1 release gates passed on 2026-10-07: 616 main-suite cases, 615 successful, one optional current-release compiler smoke skipped, zero failures/errors. Architecture/quality gates, packaging, structure/configuration, signing and signature verification passed. Real subprocess tests used pinned Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c` and Bun 1.4.2. Plugin Verifier classified Community 2025.1 and Ultimate 2026.1 as compatible with existing API usage notices. Signed metadata preserves ID `com.dearlordylord.bend.idea` and since-build 251 with no upper bound. Independent Standards and Spec reviews of the navigation changes found no actionable findings; release metadata received implementer self-review under REVIEWER.md.

- Signed ZIP SHA-256: `b7a7047228eb9858b05e21d565ce995167c34b10cdb0d316485ade888c3df33c`.
- Portable JAR SHA-256: `64b8af6b3f4d35e64b279528835f639906171c35cd54812e100371e58c4bad29`.
- Public certificate SHA-256: `4ee352701ce7e03a2c5a78af1f4ccf4d351b03e5ccdaf512a625314df3cf88cc`; existing signing identity reused.

All four GitHub release assets were downloaded anonymously and matched the local verified files and checksum manifest. The portable JAR reports `bend-format 0.1.20`. An interactive 0.1.20 installation/update in IDEA has not been exercised.

Cloudflare Pages successfully deployed feed commit `6f11793` on 2026-10-07. Ordinary requests to both `https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml` served 0.1.20. ID, name, IDE range, description and change notes match the signed plugin's packaged metadata. Anonymous downloads through both feed links matched the recorded ZIP SHA-256 again. GitHub Release v0.1.20 is published as a stable, non-latest custom-repository plugin update; the latest complete formatter release remains v0.1.19. No Marketplace update was submitted.


## 0.1.21 custom repository release

[GitHub Release v0.1.21](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.21) is tagged at verified source commit `7747f0669b5c507e434391d21eeb1b2768b6f13d`. It preserves navigation inventories across unchanged editor open/close transitions, skips unnecessary selected-root discovery and runs gutter navigation without visible progress. It also includes the merged unsaved alias lifecycle correction. CHANGELOG and packaged plugin change notes cover these behaviors.

Clean JDK 21.0.12.1 release gates passed on 2026-10-07: 624 main cases, 623 successful, one optional installed-compiler smoke skipped, zero failures/errors; two architecture tests passed. Quality, packaging, structure/configuration, signing and signature verification passed. Real compiler subprocess tests used pinned Bend 2.0.35 and Bun 1.4.2. Plugin Verifier accepted IC 2025.1 and IU 2026.1 with existing API notices. Metadata preserves ID `com.dearlordylord.bend.idea` and since-build 251 with no upper bound. Independent Standards and Spec reviews found no remaining actionable findings; release metadata received implementer self-review under REVIEWER.md.

A read-only profile using the reported request-content sources measured 237 ms cold search and 2–7 ms warm searches across five round trips without warm graph reloads. These are editor-fixture search timings, not interactive IDEA rendering measurements. Actual installation/update of 0.1.21 remains a manual smoke check.

- Signed ZIP SHA-256: `fcd1b58366a2db51f2fb936a7ce1fdfd94ac3a65e36052bf18c7007d35b60e78`.
- Portable JAR SHA-256: `a046555f1dc07f513c92cdc296c7608e59615b6dca05617da0d908ae2221108a`.
- Public certificate SHA-256: `4ee352701ce7e03a2c5a78af1f4ccf4d351b03e5ccdaf512a625314df3cf88cc`; existing signing identity reused.

All four assets were downloaded anonymously and matched the locally verified files and manifest. Distribution is limited to the user's custom plugin repository; no Marketplace submission. Native formatter archives and Homebrew remain on 0.1.19, and the shared-version portable JAR retains unchanged formatter behavior. The GitHub release remains non-latest to preserve the complete formatter release. Live feed verification is recorded after deployment.

Cloudflare Pages successfully deployed feed commit `2d89061` on 2026-10-07. Ordinary requests to both `https://idea.dearlordylord.com/updatePlugins.xml` and `https://bend-idea-plugins.pages.dev/updatePlugins.xml` served 0.1.21 with ID, name, IDE range, description and change notes matching packaged metadata. Anonymous downloads through both feed links matched the signed ZIP digest again. GitHub Release v0.1.21 is stable and non-latest; no Marketplace submission was made.
