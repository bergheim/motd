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
native_gradle() { nix develop .#native -c bash ./gradlew "$@" --stacktrace; }
pr_gradle() {
  MOTD_FUZZ_PROFILE=pr MOTD_FUZZ_SEED="$(git rev-parse HEAD)" gradle "$@"
}

git diff --check "$BASE"...HEAD
changed_shell=()
for path in "${changed[@]}"; do
  [[ "$path" == *.sh && -f "$path" ]] && changed_shell+=("$path")
done
[ "${#changed_shell[@]}" -eq 0 ] || bash -n "${changed_shell[@]}"

matches '^\.github/(workflows/.*\.ya?ml|actions/)' && nix develop -c actionlint
matches '\.(kt|kts)$' && gradle ktlintCheck

build_changed='^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew|gradle/)'
irc_changed='^irc/(src/|build\.gradle\.kts$)'
app_tested_changed='^app/(schemas/|src/(main|test|testDebug)/|build\.gradle\.kts$)'
production_android_changed='^(app/src/main/|app/build\.gradle\.kts$|irc/src/main/)|'"$build_changed"
if matches "$irc_changed|$build_changed"; then
  pr_gradle :irc:test
fi
if matches "$app_tested_changed|^irc/src/main/|$build_changed"; then
  pr_gradle :app:testDebugUnitTest
fi
if matches "$production_android_changed|^app/src/debug/"; then
  MOTD_LINT_DEBUG=false
  matches "^app/src/debug/|^app/build\\.gradle\\.kts$|$build_changed" && MOTD_LINT_DEBUG=true
  MOTD_LINT_DEBUG="$MOTD_LINT_DEBUG" nix develop -c bash ./tools/android-lint.sh
fi

matches '^app/src/androidTest/' && gradle :app:compileE2eAndroidTestKotlin
if matches '^app/(src/(main|debug|release)/(res/|AndroidManifest\.xml)|build\.gradle\.kts$|libs/)|^third_party/sing-box/source\.lock$|^gradle/|^gradlew$|^(build|settings)\.gradle\.kts$|^gradle\.properties$'; then
  native_gradle :app:assembleDebug :app:assembleRelease
fi
if matches '(^|/)(build\.gradle\.kts|libs\.versions\.toml|settings\.gradle\.kts)$'; then
  if gradle :app:dependencies --configuration releaseRuntimeClasspath | grep -Ei 'firebase|play-services'; then
    echo "Google-only dependencies reached the FOSS runtime classpath" >&2
    exit 1
  fi
fi
if matches '^test/e2e/.*\.sh$|^tools/(ci-paths|android-lint)\.sh$|^\.github/.*\.ya?ml$'; then
  bash ./test/e2e/validate.sh
fi
if matches '^app/(schemas/|src/(main|test|testDebug)/.*data/db/)'; then
  git diff --exit-code -- app/schemas
  test -z "$(git ls-files --others --exclude-standard -- app/schemas)"
fi

[ -z "$(git status --porcelain)" ] || {
  echo "pre-push checks changed the working tree" >&2
  exit 1
}
echo "Pre-push checks passed for $(git rev-parse --short HEAD)"
