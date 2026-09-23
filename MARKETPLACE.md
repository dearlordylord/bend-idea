# Marketplace releases

Bend2's plugin ID is `com.dearlordylord.bend.idea`; its listing name is `Bend2`. Build with JDK 21 against IntelliJ IDEA 2025.1. The plugin declares no upper IDE build limit; Plugin Verifier checks Community 2025.1 and Ultimate 2026.1. Verify each newer IDE release before claiming support.

Quint IDEA's update process is a local `./gradlew publishPlugin` invocation with `PUBLISH_TOKEN`, after bumping its version. It is not a scheduled or CI release. Bend IDEA follows that process for **updates after the first manual Marketplace upload**, with author signing required by this build's publish task. JetBrains documents the [first-upload rule](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html) and [signing tasks](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

## Current update: 0.1.1

Marketplace already has version `0.1.0` in the default channel and rejects another ZIP with that version. The next upload is `build/distributions/bend-idea-0.1.1-signed.zip` (SHA-256: `016bd861f356dff2a6a1f54bc0f3715f0a2221b7d6fdf087467ae491abeadffe`). Its embedded plugin ID is `com.dearlordylord.bend.idea`, its version is `0.1.1`, and its IDE range starts at build `251` with no upper bound. The release notes describe import-path navigation and compatibility with newer IDEA builds. Upload this signed archive as an update to the existing plugin ID and channel; do not upload an unsigned archive or reuse `0.1.0`.

The `0.1.1` release build passed `check` (167 editor tests and 2 architecture tests), `buildPlugin`, `verifyPlugin`, `verifyPluginStructure`, `verifyPluginProjectConfiguration`, `signPlugin`, and `verifyPluginSignature`. Plugin Verifier reported compatibility with IC-251.23774.435 and IU-261.22158.277; it also reported deprecated, scheduled-for-removal, and experimental API usages. This is compatibility evidence for those two builds, not a claim that every later build has been tested. A fresh IDE installation and Marketplace acceptance are still to be checked.

The author signing key and certificate are stored outside this repository:

```text
/home/node/.config/bend-idea/signing/private.pem
/home/node/.config/bend-idea/signing/chain.crt
```

The private key has file mode `600`, and its directory has mode `700`. **Back up this signing identity securely before the environment is replaced.** Never commit or paste the private key or Marketplace token into this repository.

The original `0.1.0` upload established the Marketplace listing. For this update, use that listing's update flow or the configured `publishPlugin` task with a Marketplace token. JetBrains documents the [update API](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html) and [Gradle publishing task](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).

## Publish an update

1. Increment `pluginVersion` in `gradle.properties`. Marketplace will reject another upload with the same version in a channel. Update the `<change-notes>` section in `src/main/resources/META-INF/plugin.xml` and check that its feature claims match the release. Keep the open-ended IDE range; verify newer releases before claiming support.
2. Use a full JDK 21. Set `BEND_IDEA_SIGNING_DIR` to the directory holding `private.pem` and `chain.crt`. Set `PUBLISH_TOKEN` from the Marketplace account's **My Tokens** page using a secret manager or local environment; keep it out of Git and command history. On this machine, the signing directory is `/home/node/.config/bend-idea/signing`.
3. Clear old build distributions and run all release gates:

   ```sh
   ./gradlew --no-daemon clean check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration signPlugin verifyPluginSignature
   ```

4. Check `build/distributions/bend-idea-<version>-signed.zip` and install it from disk in a fresh supported IDE. Open a `.bend` file and confirm editor registration and the light/dark file icon. Confirm the signed ZIP has `META-INF/plugin.xml`, `META-INF/pluginIcon.svg`, and `META-INF/pluginIcon_dark.svg` in its main JAR. Delete unsigned and older ZIPs from `build/distributions/`.
5. Run `./gradlew --no-daemon publishPlugin` with `BEND_IDEA_SIGNING_DIR` and `PUBLISH_TOKEN`, or upload the signed ZIP through the existing Marketplace listing. Check the resulting update and approval status. Do not submit the same version a second time.

`publishPlugin` uploads an update; it does not replace the first manual Marketplace upload. Running `publishPlugin --dry-run` only checks the Gradle task graph and does not contact Marketplace. No release CI job publishes automatically. CI uploads a signed artifact only when its signing secrets are configured; it never uploads the unsigned intermediate.
