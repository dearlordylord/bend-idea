# Installing Bend2 before Marketplace publication

Checked 2026-09-26 against this repository and first-party JetBrains and GitHub documentation. The signed 0.1.4 ZIP is now available as a [GitHub Release asset](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.4). The proposed update feed has not been deployed or tested in an IDE.

## Recommendation

**Publish each verified, signed plugin ZIP as a GitHub Release asset and link that release from `README.md`.** A user downloads the asset, opens **Settings → Plugins → gear icon → Install Plugin from Disk**, selects the ZIP, and restarts if prompted. JetBrains documents local ZIP/JAR installation; GitHub Releases provide a stable page for binaries and release notes. Do not give users GitHub's automatic source-code ZIP: it is source, not the packaged IntelliJ plugin. [JetBrains install instructions](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk), [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases), [GitHub release asset instructions](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository#creating-a-release)

**Add a custom plugin repository if automatic update discovery is worth one more user setup step.** Host an `updatePlugins.xml` over HTTPS (GitHub Pages is one static hosting option) with a version-specific HTTPS URL to the signed GitHub Release asset. A user adds the XML URL once under **Settings → Plugins → gear icon → Manage Plugin Repositories**. The IDE can then discover/install the plugin and offer subsequent updates through the normal plugin UI. JetBrains requires the XML feed and an HTTPS plugin download URL; it does not document a direct remote-ZIP install field in the **Install Plugin from Disk** flow. [JetBrains custom repository format](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html), [IDE repository setup and update behavior](https://www.jetbrains.com/help/idea/managing-plugins.html#custom_plugin_repositories), [GitHub Pages publishing choices](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)

The order above is a recommendation for this repo: use the available Release download now; add the feed when there are repeated pre-Marketplace releases. A feed improves repeat updates but requires a maintained XML URL, hosted asset, and version metadata. A GitHub Actions artifact is a CI handoff, not the lasting public download: GitHub's default artifact retention is 90 days, while Releases are intended for distributing packaged software. [GitHub workflow artifact retention](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts), [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)

## Fit to the current build

[`README.md`](../README.md) says to distribute only `build/distributions/*-signed.zip`; [`build.gradle.kts`](../build.gradle.kts) configures `signPlugin` and `verifyPluginSignature`; [`MARKETPLACE.md`](../MARKETPLACE.md) records the JDK 21 release gates. The [`verify.yml`](../.github/workflows/verify.yml) workflow uploads a signed ZIP as an Actions artifact only when signing secrets are available; it does not create a GitHub Release. The 0.1.4 release was created separately from the verified signed candidate. Its ZIP and public signing certificate are available on the [release page](https://github.com/dearlordylord/bend-idea/releases/tag/v0.1.4); no update feed is live yet.

Keep the existing plugin ID, `com.dearlordylord.bend.idea`, and raise `pluginVersion` for every update. The current build sets `since-build="251"` without an upper limit; its tested matrix is Community 2025.1 and Ultimate 2026.1. An open-ended compatibility declaration does not establish compatibility with untested IDE releases. The feed's ID, version, and `idea-version` must match the packaged `plugin.xml`. [JetBrains custom repository format](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html), [JetBrains plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)

## Maintainer release procedure

1. Complete the existing JDK 21 release gates in [`MARKETPLACE.md`](../MARKETPLACE.md), including `check`, `buildPlugin`, `verifyPlugin`, `signPlugin`, and `verifyPluginSignature`; use the pinned Bend/Bun test inputs. Inspect the final `*-signed.zip` and record its SHA-256. JetBrains says the packaged ZIP in `build/distributions` is what users install or repositories host. [JetBrains publishing guide](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html), [JetBrains signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
2. Create a GitHub Release for the exact tested commit and version tag (for example, `v0.1.5`), attach **the signed ZIP itself** as a binary asset, and state the compatible IDE builds and install instructions in the release notes. GitHub supports binary attachments and versioned releases. Leave the unsigned `buildPlugin` ZIP out of the release. [GitHub release instructions](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository#creating-a-release)
3. Verify the public release page and signed asset download from a signed-out session. Put the release page or a versioned asset link in `README.md`; do not advertise a draft release or a URL before the asset is present. A version-specific asset URL has the form `https://github.com/dearlordylord/bend-idea/releases/download/v0.1.5/bend-idea-0.1.5-signed.zip` **if** that tag and exact filename are published. The 0.1.4 public download was checked against the recorded SHA-256. GitHub documents release asset URL patterns and direct latest-asset links. [GitHub linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases), [GitHub Releases API asset example](https://docs.github.com/en/rest/releases/releases)
4. If offering update notices, publish the XML below at a stable HTTPS URL and add that URL to the README. GitHub Pages can publish static content from a branch/folder or an Actions deployment; it must be enabled and its public XML URL checked. Update the XML **after** the new asset is downloadable. This last ordering is an operational inference to avoid a feed advertising a missing ZIP. [JetBrains custom repository setup](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html), [GitHub Pages publishing](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)

For this project's current metadata, a future feed entry would look like this (illustrative, **not live**):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="com.dearlordylord.bend.idea"
          url="https://github.com/dearlordylord/bend-idea/releases/download/v0.1.5/bend-idea-0.1.5-signed.zip"
          version="0.1.5">
    <idea-version since-build="251"/>
    <name>Bend2</name>
  </plugin>
</plugins>
```

JetBrains requires one `<plugin>` entry per ID in a given XML file and says its ID/version/IDE range must match the archive metadata. If future releases need separate IDE compatibility tracks, provide separate feed URLs or a server response selected by the IDE's `build` parameter. [JetBrains custom repository schema and constraints](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)

## User instructions to publish with the asset

1. Use IntelliJ IDEA 2025.1 or a version listed as verified in the release notes.
2. Download the `bend-idea-<version>-signed.zip` asset from the [GitHub Releases page](https://github.com/dearlordylord/bend-idea/releases). Choose the named asset, not **Source code (zip)**.
3. In IDEA, open **Settings → Plugins → gear icon → Install Plugin from Disk**, choose the downloaded ZIP, and restart if requested. [JetBrains install instructions](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)
4. If the signing certificate is self-signed, download the [public signing certificate](https://github.com/dearlordylord/bend-idea/releases/download/v0.1.4/bend-idea-signing-certificate.crt) and add it under **Settings → Plugins → Manage Plugin Certificates** when the IDE asks for trust. Do not distribute `private.pem`. JetBrains documents this trust path for custom repositories and manually installed signed plugins. [JetBrains plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html#using-self-signed-certificates)
5. For later versions, repeat the disk installation with the newer signed ZIP, or add the custom feed URL once under **Manage Plugin Repositories** when one is published. The IDE's Plugins/Updates UI can then report repository updates. [JetBrains plugin management](https://www.jetbrains.com/help/idea/managing-plugins.html)

## Publication transition

The 0.1.4 signed archive has been submitted to Marketplace but was recorded as **Under review**, not publicly available, in [`MARKETPLACE.md`](../MARKETPLACE.md). Check its current approval state before changing installation instructions. Once Marketplace is live, use its normal listing as the default install path. Keeping the same plugin ID preserves the product identity; removing the temporary custom feed from user instructions after the transition avoids competing update sources. The latter is a project recommendation, not a stated JetBrains requirement. [JetBrains custom repository ID guidance](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html), [JetBrains Marketplace publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)

The 0.1.4 Release used the already verified signed archive; no new plugin archive was built or installed as part of this distribution work.
