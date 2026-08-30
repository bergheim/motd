# Testing and verification

Run all Gradle commands through the repository Nix shell. During development,
run the nearest test method, then its class. Changed behavior needs a new or
updated regression unless an existing named test already exercises that branch.
Run `nix develop -c ./gradlew ktlintCheck` before handoff; use `ktlintFormat` to
apply enforced style. Before pushing a clean candidate commit, run
`./tools/prepush.sh` once for path-selected CI parity.

## Command matrix

| Changed surface | Required local checks |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | Nearest `:irc` test method, then its class |
| Android repositories, services, preferences, or ViewModels | Nearest `:app` test method, then its class |
| Room entities/schema/migrations | Nearest database test; pre-push schema verification |
| Compose Kotlin | Nearest Robolectric behavior test in `testDebug`; its Gradle task already compiles the app |
| Resources/manifest/packaging | Nearest test when behavior changed, then `:app:assembleDebug` |
| Instrumentation source | `:app:compileE2eAndroidTestKotlin`; emulator only when lower tiers cannot prove behavior |
| Ordinary app user journey | Relevant unit/integration test; assemble only when an APK is needed |
| Cross-module or release-sensitive work | Nearest tests while iterating, then `./tools/prepush.sh` once |

Target one method while iterating, then its class before handoff:

```sh
nix develop -c ./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-test-class.method>' --stacktrace
```

Use `:irc:test` instead for IRC tests. Run `:app:assembleDebug` only when
resources, manifest, packaging, or an installable APK must be checked. Pre-push
runs the debug unit/Robolectric suite once plus release lint for production
Android changes; release unit tests duplicate shared coverage and are not part
of the gate. Emulator E2E remains hosted unless lower tiers cannot prove behavior.

## Pre-push gate

Commit checkpoints freely. Once the candidate tree is committed and clean, run:

```sh
./tools/prepush.sh
```

The script compares `HEAD` with `origin/main`, uses the exact commit as the PR
fuzz seed, and runs only applicable deterministic non-emulator checks. Override
the comparison base with `MOTD_PREFLIGHT_BASE=<ref>` when needed. A failing check
must be fixed and committed before rerunning; do not stack more pushes on red CI.

## Deterministic generated tests

Generated tests default locally to checked-in regressions plus eight generated
cases per target. Required CI explicitly selects the PR workload and replaces the
seed with the candidate commit; `.github/workflows/fuzz.yml` selects the larger
nightly profile.

The nightly workflow runs one fresh-seed shard for each module. The IRC shard
covers 200,000 parser cases and 75,000 mapper cases. The app shard covers 75,000
presentation cases, 1,500 canonical-timeline cases with 128 operations each,
and 500 EventProcessor cases. Job summaries report effective counts, index
ranges, and any manual overrides.

- `MOTD_FUZZ_SEED=<text>` selects an exact seed.
- `MOTD_FUZZ_CASE=<index>` replays one independently seeded case.
- `MOTD_FUZZ_PROFILE=pr|nightly` selects a hosted workload; unset uses the local workload.
- `MOTD_FUZZ_CASES=<count>` and `MOTD_FUZZ_STEPS=<count>` override campaign size.
  Only positive values apply (`0` falls back to the selected profile).
- `MOTD_FUZZ_SHARD=<zero-based index>` offsets generated case indices by one
  configured case-count, allowing parallel jobs to cover disjoint cases under
  the same reproducible seed. Exact `MOTD_FUZZ_CASE` replay ignores the shard.

Failures print an exact Nix/Gradle replay command and write the generated
operation trace below the module's `build/fuzz-failures/` directory. Minimize a
real failure into a named JUnit regression and retain its target, generator
version, seed, case, and fixture in that module's
`src/test/resources/fuzz/regressions.tsv` file.

## Device and E2E selection

- Do not run the headless emulator suite during routine local development. It
  materially slows the maintainer's workstation. Local verification stops at
  nearest unit/integration tests and assembly only when the matrix requires it.
- `.github/workflows/ci.yml` owns the complete required gate. Its `headless` job runs exactly
  four isolated `@FastHeadlessE2e` methods on API34 Pixel 6 AOSP, while the parallel
  component tier runs fixture-free Compose/UI tests through `:app:testDebugUnitTest` under
  Robolectric. Documentation-only
  changes run the path classifier and stable gate without booting Android jobs.
  Push the candidate commit and require the complete CI gate to pass before
  tagging a release. An `action_required` external-PR run is not evidence:
  approve it and wait for the exact candidate or integration SHA to pass before
  merging.
- Use a physical device for hardware- or OS-integration evidence: input latency,
  scrolling performance, wallpaper/rendering quality, background lifecycle,
  notifications and UnifiedPush, system pickers, certificates outside the
  fixture trust flow, and a real release installation. Only do this when the
  maintainer explicitly asks for device validation.
- Only when lower-level checks cannot validate behavior, reproduce the focused
  CI suite with `./test/e2e/headless.sh fast`.
- Fixture-free Compose/component tests live in `app/src/testDebug` and run with
  `:app:testDebugUnitTest`; only real-stack journeys and their support remain in `androidTest`.
- `test/e2e/fast-suite.sh` is the canonical fast-suite launcher and fixture
  argument source for local direct instrumentation and connected CI. Do not duplicate its
  annotation or fixture arguments in workflow YAML.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local headless `full` command runs A-H/J/V/R before teardown phase I on the isolated emulator;
  the exhaustive hosted runbook is manual-only, while scheduled proxy and ZNC probes stay enabled.
- Use `:app:assembleE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

When explicitly debugging CI E2E, follow
[`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
