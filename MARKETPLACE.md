# Marketplace releases

Bend2's plugin ID is `com.dearlordylord.bend.idea`; its listing name is `Bend2`. Build with JDK 21. The plugin currently targets IntelliJ IDEA 2025.1 and declares no upper IDE build limit; Plugin Verifier has checked Community 2025.1 and Ultimate 2026.1. Verify each newer IDE release before claiming support.

## Candidate 0.1.2

The repository records version `0.1.1`; this candidate advances the package version to `0.1.2`. The clean JDK 21 release gates passed on 2026-09-24: 270 tests, 0 failures, 0 errors, 0 skipped; packaging, structure, project configuration, and Plugin Verifier tasks also passed. Plugin Verifier reported compatibility with Community 2025.1 and Ultimate 2026.1, along with deprecated, scheduled-for-removal, experimental, and internal API usages.

The generated local candidate is the unsigned `build/distributions/bend-idea-0.1.2.zip`. The candidate branch is untagged; no signed 0.1.2 artifact or Marketplace submission is recorded.

Changes since `2df22df`:

- Add a Bend structure view, source-preserving formatting, and binding-aware rename.
- Add workspace declaration search through Go to Symbol.
- Harden current and background checking with immutable state transitions, conservative CLI verdicts, and exact source mapping for imported compiler diagnostics.
- Make real compiler evidence reproducible with pinned Bend and Bun inputs, offline/check-only fixtures, and CI provenance reporting; enforce Scala formatting, selected compiler warnings, and scoped policy mutation checks.

## Marketplace listing status: unresolved release blocker

On 2026-09-24, the official [JetBrains plugin details API](https://plugins.jetbrains.com/docs/marketplace/plugin-details.html) was queried for this plugin's XML ID at [the public listing endpoint](https://plugins.jetbrains.com/plugins/list?pluginId=com.dearlordylord.bend.idea). It returned HTTP 200 with an empty `<plugin-repository/>` response. This conflicts with earlier repository guidance that treated 0.1.0 as an established listing and 0.1.1 as an update. The empty response does not prove whether the plugin was never listed, was removed, or is awaiting indexing; the Marketplace account status has not been verified.

The previous repository record contains a signed 0.1.1 archive hash (`016bd861f356dff2a6a1f54bc0f3715f0a2221b7d6fdf087467ae491abeadffe`) and local build/verification results. That is evidence of a local artifact, not evidence that 0.1.1 was uploaded or accepted. Do not assume that 0.1.1 is available in the Marketplace or that 0.1.2 can be submitted as an update.

Before any upload, verify the plugin ID, existing channel versions, and approval state through the authenticated Marketplace vendor account or JetBrains support. Confirm whether the next submission is a first manual upload or an update, and whether version 0.1.2 is available in the intended channel. The authenticated account state is not verified here; `BEND_IDEA_SIGNING_DIR` and `PUBLISH_TOKEN` were unset in the candidate environment. These are current blockers to signing and submission. Leave public listing status and 0.1.1 acceptance open until they are checked.

## Validation and later release procedure

For the 0.1.2 candidate, use a full JDK 21 and run:

```sh
./gradlew --no-daemon clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

The real compiler checks require the pinned Bend checkout and Bun executable described in [`ci/bend-test-toolchain.properties`](ci/bend-test-toolchain.properties). This command builds an unsigned local ZIP; install it from disk in a fresh supported IDE and verify file registration, icons, checking, and the advertised features before considering distribution.

Once the listing, channel, and version are verified and signing credentials are available, configure the signing directory and Marketplace token through a secret manager or local environment. Keep signing keys and tokens out of Git and command history. Sign and verify the candidate, inspect the signed archive, then use the confirmed first-upload or update flow. Check the resulting upload and approval status in Marketplace; a successful upload request is not the same as approval or publication.

The CI workflow never uploads a plugin to Marketplace. On master pushes it may sign and upload a CI artifact when signing secrets are configured; that artifact is for retrieval and verification, not proof of Marketplace acceptance.
