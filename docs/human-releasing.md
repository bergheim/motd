# Releasing motd

Only cut or alter a release when the maintainer explicitly requests it. The
current automation in `.github/workflows/release.yml` is authoritative; when
this document and the workflow disagree, the workflow wins.

## Preflight

1. Inspect the branch, `git status`, the staged diff, and recent tags. Do not
   include unrelated work, and do not assume uncommitted user changes should be
   released.
2. Run the local FOSS release-parity unit/integration, lint, and build checks
   from [`human-developing.md`](human-developing.md). Do not run local emulator
   E2E.
3. Push the candidate commit and require the complete `CI` workflow — including
   its `headless-core` E2E job and final `gate` job — to pass before tagging.
4. Confirm the requested semantic version and that the `v<semver>` tag does not
   already exist locally or remotely.
5. Confirm the four signing secrets exist in GitHub: `KEYSTORE_BASE64`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

The Google/FCM distribution is paused. Do not build, sign, attach, or publish a
Google APK, and do not require Firebase client or relay configuration for a
release, until the maintainer explicitly reactivates it.

## Bump the version

Edit `gradle.properties` and commit the bump before tagging. The release
workflow derives both Android version fields from these properties and requires
the signed tag to match `v<versionName>`.

```properties
motdVersionName=0.10.8
motdVersionCode=10008
```

The version-code scheme is `major * 1000000 + minor * 1000 + patch`, which is
monotonic for the project's semantic release range and stays within Android's
signed 32-bit limit. Examples: `0.10.8 -> 10008`, `0.11.0 -> 11000`,
`1.0.0 -> 1000000`.

## Cut the release

Tag and push a GPG-signed `v<semver>` tag. Replace the example version with the
approved one.

```sh
git tag -s v0.10.8 -m "v0.10.8"
git push origin v0.10.8
```

The workflow requires the tag to match `v<versionName>` from
`gradle.properties`, uses the matching `motdVersionCode`, and embeds the tagged
commit SHA as source provenance. It then builds and signs the FOSS APK and
publishes:

- the renamed FOSS APK;
- complete corresponding libbox source;
- GPL and IBM Plex license files;
- release-specific third-party notices; and
- `SHA256SUMS`.

The release description should contain a changelog of commits since the last
version.

## Local dry-run

The tracked `.envrc` points `MOTD_KEYSTORE_PATH` at
`~/secrets/motd-release.jks`. Keep the three remaining values in
`~/secrets/motd-release.env`, outside the repository:

```sh
install -d -m 700 "$HOME/secrets"
(umask 077; touch "$HOME/secrets/motd-release.env")
chmod 600 "$HOME/secrets/motd-release.env"
${EDITOR:?Set EDITOR first} "$HOME/secrets/motd-release.env"
```

The private file is shell syntax and must define the signing credentials:

```sh
export MOTD_KEYSTORE_PASSWORD='replace with the keystore password'
export MOTD_KEY_ALIAS='replace with the signing key alias'
export MOTD_KEY_PASSWORD='replace with the signing key password'
```

Do not commit that file or put the secret values directly in `.envrc`. After
creating or changing it, reload direnv and build the supported FOSS release:

```sh
direnv allow
MOTD_SOURCE_COMMIT="$(git rev-parse HEAD)" \
  ./gradlew :app:assembleFossRelease
```

The signed APK is
`app/build/outputs/apk/foss/release/app-foss-release.apk`. Verify its signature
before installing or distributing it:

```sh
apksigner verify --verbose --print-certs \
  app/build/outputs/apk/foss/release/app-foss-release.apk
```

The command must report verification success and the expected signer
certificate. If `MOTD_KEYSTORE_PATH` is unset, Gradle can assemble an unsigned
release APK; that is not a valid release artifact.

## Failure recovery

- Inspect the failed job before changing code or secrets; distinguish runner,
  signing, Gradle, and packaging failures.
- A retry runs against the same tagged commit. A source fix on `main` is not in
  that tag.
- Do not force-move a tag or delete a published release without explicit
  maintainer direction. Prefer a new patch release after fixing and verifying
  the cause.
- If a tag has never produced a published release, recreating it is still a
  history rewrite and requires explicit approval.
- Respect the maintainer's monitoring instruction: watch CI only when asked,
  and otherwise return the release/tag reference for them to follow.

## Next: update F-Droid

Once the GitHub release is published, update the F-Droid metadata to match. See
[`human-fdroid-update.md`](human-fdroid-update.md).
