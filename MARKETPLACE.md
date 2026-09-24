# Marketplace releases

Bend2's plugin ID is `com.dearlordylord.bend.idea`; its Marketplace listing name is `Bend2`. Build with JDK 21. The plugin targets IntelliJ IDEA 2025.1 and declares no upper IDE build limit. Verify newer IDE releases before claiming support.

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

## Future updates

Increment `pluginVersion` in `gradle.properties` and update the `<change-notes>` section in `src/main/resources/META-INF/plugin.xml`. Keep the open-ended IDE range and verify newer IDE releases before claiming support.

Use a full JDK 21 and run the release gates:

```sh
./gradlew --no-daemon clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration signPlugin verifyPluginSignature
```

The real compiler checks require the pinned Bend checkout and Bun executable described in [`ci/bend-test-toolchain.properties`](ci/bend-test-toolchain.properties). Signing uses `BEND_IDEA_SIGNING_DIR` pointing to the directory containing `private.pem` and `chain.crt`. Keep those files and any Marketplace token out of Git and command history. Inspect the signed archive, then upload it through the authenticated Marketplace listing or use `publishPlugin` with `PUBLISH_TOKEN`. Confirm the channel and version before uploading. Check Marketplace for the resulting update and approval state; a successful upload is not proof of publication.

The CI workflow never publishes to Marketplace. If signing secrets are configured, its signed artifact is for retrieval and verification, not evidence of Marketplace acceptance.
