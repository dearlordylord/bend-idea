# Releasing Bend2 and bend-format

The plugin and formatter share `pluginVersion` in `gradle.properties`. Publish a
new patch version for a formatter fix; never replace assets of an existing
release. IntelliJ updates use the signed ZIP through the maintainer's custom
repository. Homebrew uses runtime archives through `dearlordylord/tap`.

## Release scope and GitHub Latest

The newest verified stable plugin release must be GitHub's **Latest**, including
custom-repository-only releases. Do not retain an older Latest merely because it
contains native formatter archives. Formatter installation links must point to
the explicit verified formatter tag, independently of the plugin Latest.

For a custom-repository-only release, publish the signed ZIP, portable JAR,
public certificate and checksum manifest; verify their anonymous downloads and
both live plugin feeds, then promote with `--prerelease=false --latest=true`.
Skip native formatter packaging and Homebrew unless they are in the authorized
scope. Verify `/releases/latest` returns the new plugin tag before reporting
completion. Record Marketplace submission separately from approval.

The native formatter and Homebrew steps below apply to a full formatter release.

## Prepare and verify the version

1. Move the plugin behavior entries from `CHANGELOG.md`'s Unreleased section to
   the new version. Keep an empty Unreleased section for subsequent work.
2. Bump `pluginVersion` and update the matching `<change-notes>` in
   `src/main/resources/META-INF/plugin.xml`. Set the distribution workflow's
   default tag to the new version. Keep installation links and the live plugin
   feed on the previous version until the new downloads are available.
3. With a full JDK 21, supply the clean pinned Bend checkout and Bun executable
   from `ci/bend-test-toolchain.properties`, plus the existing signing identity:

   ```sh
   export BEND_TEST_COMPILER_DIR=/absolute/path/to/pinned/bend
   export BEND_TEST_BUN=/absolute/path/to/pinned/bun
   export BEND_IDEA_SIGNING_DIR=/absolute/path/to/signing-directory
   ./gradlew clean check buildPlugin verifyPlugin verifyPluginStructure \
     verifyPluginProjectConfiguration signPlugin verifyPluginSignature buildFormatTool
   ```

   Full `check` and packaging/compatibility gates may instead be established by
   the **Verify** workflow for the exact versioned candidate commit. For this
   path, self-review, commit and push the candidate first; tag it only after CI
   succeeds and local packaging, signing and signature verification pass. Keep
   the full JDK 21 and pinned compiler/runtime inputs. Preserve failed local
   invocations as failed evidence, retry affected scenarios without weakening
   deadlines, and record which environment established each passing gate.

   The signing directory contains `private.pem` and `chain.crt`; never publish
   the private key. Distribute only `build/distributions/*-signed.zip`. The
   unsigned ZIP is an intermediate and may be removed after all gates finish.
   Record actual test results, skipped optional checks, verifier reports and
   signatures. Inspect the signed ZIP's packaged `plugin.xml`: ID, version,
   `since-build`, description and change notes must match the intended release.
4. Perform the required self-review under `REVIEWER.md`. Commit and push the
   reviewed source and version metadata if not already done for CI. After the
   required gates pass, tag that exact commit as `vVERSION`. Do not tag a dirty
   or unverified candidate or overwrite an existing tag.

## Publish the artifacts

Create a GitHub Release in `dearlordylord/bend-idea` for the verified tag. While
assembling the release, keep it a public prerelease with `--latest=false` so
packaging runners can download its JAR without advertising an incomplete stable
release. Attach:

- `bend-idea-VERSION-signed.zip`;
- `bend-format-tool-VERSION.jar`;
- `bend-idea-signing-certificate.crt`, copied from the public certificate chain;
- `SHA256SUMS.txt`, containing the SHA-256 of these three files.

Write release notes from the versioned changelog, with the verified IDE matrix,
formatter/compiler evidence and the certificate installation instructions.
Use `gh release create --notes-file PATH` to preserve the exact notes.

Run the committed distribution workflow against the release tag:

```sh
gh workflow run formatter-distributions.yml --ref vVERSION \
  -f release_tag=vVERSION -F publish=true
```

Wait for all five platform jobs and the publish job to succeed. Each runner
verifies the release JAR's digest, packages its own Java 21 runtime, tests the
extracted command without system Java and only then uploads archives. The final
job attaches the five archives and `BEND-FORMAT-SHA256SUMS.txt` to the release.
Inspect the actual workflow results; dispatching the workflow is not completion.
Download the release assets anonymously and compare their hashes to both
manifests and the locally verified signed ZIP/JAR. Do not update Homebrew or the
plugin feed to a missing or unverified artifact.

## Update Homebrew

Clone `dearlordylord/homebrew-tap` and update `Formula/bend-format.rb`. Change all
four macOS/Linux URLs to the new release version. Replace each `sha256` with the
matching archive hash from the downloaded `BEND-FORMAT-SHA256SUMS.txt`, rather
than the JAR hash. Windows remains a manual archive installation.
Keep formula tests aligned with the command's public output and arguments:
`--version` now prints `bend-format VERSION`; test `--help` and argument errors
as well as formatting. Do not update the tap to an unreleased build.

Commit and push the formula. Its `Install and test` workflow runs `brew style`,
`brew audit --strict`, actual installation, command/version and formula tests,
and uninstall on macOS ARM64/x64 and Linux ARM64/x64. Wait for all four jobs to
pass. On an available Homebrew installation, also verify the published update
with `brew update`, `brew upgrade bend-format` (or install), and `brew test
bend-format`. If local Homebrew is unavailable, report that boundary and retain
the real platform CI evidence. Never report a formula edit as a tested install.

## Update the IntelliJ custom repository

After the signed asset is publicly downloadable, update `docs/updatePlugins.xml`
with its URL and the metadata from its packaged `plugin.xml`. Preserve the ID
`com.dearlordylord.bend.idea` and the tested open-ended range (`since-build=251`).
Update versioned download links in `README.md` and `docs/bend-format.md`, and
record release evidence in `MARKETPLACE.md` without inventing Marketplace
submission or approval.

Push the documentation/feed commit to `master`. The Git-connected Cloudflare
Pages project `bend-idea-plugins` deploys `docs/` automatically. Check both:

- `https://idea.dearlordylord.com/updatePlugins.xml`;
- `https://bend-idea-plugins.pages.dev/updatePlugins.xml`.

Both must advertise the new version and signed ZIP with matching metadata. Verify
its anonymous download and SHA-256 again. The existing trust identity is reused;
users install through **Manage Plugin Repositories**, or use **Install Plugin
from Disk** with the signed ZIP. An actual new-version install/update in the IDE
is a manual smoke check; an XML fetch is only publication evidence.

Once archives, Homebrew CI and custom-feed publication are verified, mark the
GitHub Release stable and latest. Record the release commit/tag, tap commit,
workflow runs, artifact hashes and any remaining untested manual steps.
Marketplace submission is a separate distribution step: preparing a signed ZIP
or publishing the custom feed does not submit or approve a Marketplace update.
