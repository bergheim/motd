#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${MOTD_PREFLIGHT_BASE:-origin/main}"
cd "$REPO"

git rev-parse --verify "$BASE^{commit}" >/dev/null
[ -z "$(git status --porcelain)" ] || {
  echo "pre-push checks require a clean committed tree" >&2
  exit 1
}
mapfile -t changed < <(git diff --name-only "$BASE"...HEAD)
[ "${#changed[@]}" -gt 0 ] || {
  echo "No changes since $BASE"
  exit 0
}

matches() { printf '%s\n' "${changed[@]}" | grep -Eq "$1"; }
gradle() { nix develop -c bash ./gradlew "$@" --stacktrace; }
pr_gradle() {
  MOTD_FUZZ_PROFILE=pr MOTD_FUZZ_SEED="$(git rev-parse HEAD)" gradle "$@"
}

git diff --check "$BASE"...HEAD
mapfile -t changed_shell < <(printf '%s\n' "${changed[@]}" | grep -E '\.sh$' || true)
[ "${#changed_shell[@]}" -eq 0 ] || bash -n "${changed_shell[@]}"
matches '\.(kt|kts)$' && gradle ktlintCheck

build_changed='^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew|gradle/)'
irc_changed='^irc/(src/|build\.gradle\.kts$)'
app_shared_changed='^app/(schemas/|src/(main|test)/|build\.gradle\.kts$)'
if matches "$irc_changed|$build_changed"; then
  pr_gradle :irc:test
fi
if matches "$app_shared_changed|$build_changed"; then
  pr_gradle :app:testDebugUnitTest :app:testReleaseUnitTest
else
  matches '^app/src/testDebug/' && pr_gradle :app:testDebugUnitTest
  matches '^app/src/testRelease/' && pr_gradle :app:testReleaseUnitTest
fi

matches '^app/src/androidTest/' && gradle :app:compileE2eAndroidTestKotlin
if matches '^app/(src/(main|debug|release)/(res/|AndroidManifest\.xml)|build\.gradle\.kts$)|^third_party/sing-box/source\.lock$|^gradle/|^gradlew$|^(build|settings)\.gradle\.kts$|^gradle\.properties$'; then
  gradle :app:assembleDebug :app:assembleRelease
fi
if matches '(^|/)(build\.gradle\.kts|libs\.versions\.toml|settings\.gradle\.kts)$'; then
  if gradle :app:dependencies --configuration releaseRuntimeClasspath | rg -i 'firebase|play-services'; then
    echo "Google-only dependencies reached the FOSS runtime classpath" >&2
    exit 1
  fi
fi
if matches '^test/e2e/.*\.sh$|^\.github/.*\.ya?ml$'; then
  ./test/e2e/validate.sh
fi
if matches '^app/(schemas/|src/(main|test)/.*data/db/)'; then
  git diff --exit-code -- app/schemas
  test -z "$(git ls-files --others --exclude-standard -- app/schemas)"
fi

[ -z "$(git status --porcelain)" ] || {
  echo "pre-push checks changed the working tree" >&2
  exit 1
}
echo "Pre-push checks passed for $(git rev-parse --short HEAD)"
