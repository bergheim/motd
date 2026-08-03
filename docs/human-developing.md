# Building, linting, and testing motd

Enter the repository Nix shell first so the JDK and Android SDK match CI:

```sh
nix develop
```

direnv loads the same shell automatically via `.envrc` if you use it. All
Gradle commands below assume you are already inside this shell.

## Prerequisites

- Before rebuilding the bundled libbox AAR, initialize submodules recursively:

```sh
git submodule update --init --recursive
```

- The Google/FCM flavor is dormant. Do not run Google Gradle tasks or build a
  Google APK unless the maintainer explicitly reactivates it.

## Build

```sh
./gradlew :irc:test                   # protocol tests (pure JVM)
./gradlew :app:testFossDebugUnitTest  # app unit tests (Robolectric)
./gradlew :app:assembleFossDebug      # Google-free arm64 debug APK
```

The debug APK lands under `app/build/outputs/apk/foss/debug/`. Install it with
`adb install`. The debug build carries the `.debug` application-id suffix, so
it can coexist with a release install.

The embedded VLESS + REALITY transport uses bundled libbox, which is
arm64-v8a-only. APKs built from this source tree must not be installed on
32-bit ARM or x86 devices. Other ABI support needs a separately pinned and
verified libbox artifact.

## Lint

Lint warnings are errors. Run with the warm daemon and the repo's bounded worker
cap (`org.gradle.workers.max` in `gradle.properties`). Do not add
`--no-daemon --max-workers=1`: it cost roughly 30x the wall-clock (~300s vs ~10s
warm) for no deterministic race protection. Lint can rarely hit the
`ModifierDeclarationDetector` classloader race; if it does, just re-run (the warm
daemon makes a re-run ~10s). CI and release wrap lint in a bounded retry.

```sh
./gradlew :app:lintFossDebug :app:assembleFossDebug --stacktrace
```

For release parity:

```sh
./gradlew :app:lintFossRelease --stacktrace
```

## Choose checks by changed surface

Start with the narrowest useful check and expand when a change crosses
boundaries.

|Changed surface                                           |Required checks                                                                                                       |
|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
|Documentation only                                        |`git diff --check`; verify links, commands, and referenced paths                                                      |
|Shell harness/config                                      |`bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run                     |
|IRC parser/client/transport                               |`./gradlew :irc:test --stacktrace`                                                                                    |
|Android repositories, services, preferences, or ViewModels|`./gradlew :app:testFossDebugUnitTest --stacktrace`                                                                   |
|Firebase relay                                            |`npm ci --prefix firebase/functions --ignore-scripts`, then `npm test` and `npm audit --omit=dev` with the same prefix|
|Compose/resources/manifest                                |App unit tests, FOSS lint, and the FOSS debug assembly                                                                |
|Ordinary app user journey                                 |Relevant unit/integration tests plus FOSS lint/build; rely on required CI for E2E                                     |
|Cross-module or release-sensitive work                    |The full release-parity Gradle command below                                                                          |

Full release-parity Gradle verification:

```sh
./gradlew \
  :irc:build \
  :app:testFossDebugUnitTest :app:testFossReleaseUnitTest \
  :app:lintFossDebug :app:lintFossRelease :app:assembleFossRelease \
  --stacktrace
```

## Device and E2E testing

Do not run the headless emulator suite during routine local development; it
materially slows the maintainer's workstation. Local verification stops at the
relevant unit/integration tests, lint, and builds above.

For the local stack, physical-device, and emulator harnesses, follow
[`../test/e2e/README.md`](../test/e2e/README.md). The agent-facing selection
matrix in [`../.agents/testing.md`](../.agents/testing.md) describes which
suite fits which task. Those harnesses have their own shell requirements
documented alongside them.

## Generated (fuzz) tests

The ordinary `:irc:test` and `:app:testFossDebugUnitTest` tasks include bounded,
seeded generated tests. Their defaults are stable; CI replaces the seed with
the pull-request commit and a nightly workflow runs the larger profile.
Override the campaign with environment variables:

- `MOTD_FUZZ_SEED=<text>` — select an exact seed.
- `MOTD_FUZZ_CASE=<index>` — replay one independently seeded case.
- `MOTD_FUZZ_PROFILE=pr|nightly` — select the bounded workload.
- `MOTD_FUZZ_CASES=<count>` / `MOTD_FUZZ_STEPS=<count>` — override campaign size.
- `MOTD_FUZZ_SHARD=<zero-based index>` — offset generated case indices so
  parallel jobs cover disjoint cases under the same seed.

Failures print an exact Nix/Gradle replay command and write the operation trace
below the module's `build/fuzz-failures/` directory. The regression-minimization
workflow is documented in [`../.agents/testing.md`](../.agents/testing.md).

## Architecture

For data flow, connection ownership, and module boundaries, see
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).
