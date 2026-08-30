#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

printf '%s\n' 'java.lang.NoClassDefFoundError: androidx.compose.lint.ModifierDeclarationDetector' >"$scratch/classloader.log"
printf '%s\n' 'app/Foo.kt:12: Error: Modifier parameter must be first [ModifierParameter]' >"$scratch/finding.log"

[ "$(bash "$ROOT/tools/android-lint.sh" --classify "$scratch/classloader.log")" = infrastructure ]
[ "$(bash "$ROOT/tools/android-lint.sh" --classify "$scratch/finding.log")" = deterministic ]
