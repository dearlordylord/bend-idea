# Marketplace releases

Bend2's plugin ID is `com.dearlordylord.bend.idea`; its Marketplace listing name is `Bend2`. Build with JDK 21. The plugin targets IntelliJ IDEA 2025.1 and declares no upper IDE build limit.

## 0.1.3 rollback submission

This package restores the source baseline from commit `2df22df` (the 0.1.1 release candidate), bumps the plugin version and release notes, and sets Gradle's packaged Marketplace name to the existing listing name `Bend2`.

The release gates passed on 2026-09-24 with JDK 21.0.10 and pinned Bend source `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b`, using Bun 1.4.2: 167 tests, 0 failures, 0 errors, and 0 skipped; architecture, packaging, project configuration, and signature verification also passed. Plugin Verifier reported compatibility with Community 2025.1 and Ultimate 2026.1. It also reported 9 scheduled-for-removal API usages, deprecated and experimental API usages, and two Internal API usages: `Configurable.getDisplayNameFast()` is both invoked and overridden in `BendSettingsConfigurable`.

The signed archive is `build/distributions/bend-idea-0.1.3-signed.zip` (SHA-256: `80c25586c0e909b39c78521fc377b0f995212ed5dd78935e6bf665130ce12fdc`). Signature verification passed. Marketplace accepted it as Stable update `1178310` on 2026-09-24. It is under review; Marketplace says Support will conduct additional checks before publication. Do not describe it as public until the status changes.

The verifier reports two Internal API usages in this rollback artifact: `Configurable.getDisplayNameFast()` is invoked and overridden in `BendSettingsConfigurable`. The follow-up fix is committed and pushed as `codex/fix-internal-api` at `6e91d38`; verification of the current source reports no Internal API usages on IC 2025.1 or IU 2026.1. Marketplace accepted the signed 0.1.4 fixed build as Stable update `1178547` on 2026-09-24. It is under review; do not describe it as public until Marketplace confirms approval.

## Previous submission

JetBrains uploaded 0.1.2 as Stable update 1178294 but reported one Internal API usage and requested a corrected upload. Its Marketplace status remained under review at the last check. The vendor page had versions 0.1.0 and 0.1.1 under review as well.

## Future updates

Increment `pluginVersion` in `gradle.properties` and update the `<change-notes>` section in `src/main/resources/META-INF/plugin.xml`. Keep the open-ended IDE range and verify newer IDE releases before claiming support.

Use a full JDK 21 and run the release gates:

```sh
./gradlew --no-daemon clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration signPlugin verifyPluginSignature
```

Compiler tests use the pinned Bend checkout and Bun version defined in the release validation setup. Signing uses `BEND_IDEA_SIGNING_DIR` pointing to the directory containing `private.pem` and `chain.crt`; the maintainer's signing identity is stored in the iCloud Drive folder `bend-idea-signing`. Keep the private key and any Marketplace token out of Git and command history. Upload the signed archive through the authenticated Marketplace listing or use `publishPlugin` with `PUBLISH_TOKEN`. Verify the channel and version before upload, then confirm the Marketplace approval state.

The CI workflow does not publish to Marketplace. A successful upload is not proof of public approval or publication.
