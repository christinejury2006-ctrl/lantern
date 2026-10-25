#!/usr/bin/env bash
# Compiles the shelf's PBR materials with Filament's own matc, for the APK to ship as bytes.
#
# The version here must match the Filament runtime in app/build.gradle.kts: a material package
# produced by a different matc can be rejected by the runtime, and that failure looks exactly
# like a shader error.
set -euo pipefail

FILAMENT_VERSION="1.72.0"
OUT="app/src/main/assets/materials"

mkdir -p "$OUT" .matc

if [ ! -x .matc/matc ]; then
  echo "fetching matc $FILAMENT_VERSION"
  curl -sSL -o filament.tgz \
    "https://github.com/google/filament/releases/download/v${FILAMENT_VERSION}/filament-v${FILAMENT_VERSION}-linux.tgz"
  tar xzf filament.tgz
  rm -f filament.tgz
  FOUND="$(find . -path ./.matc -prune -o -type f -name matc -print | head -1)"
  if [ -z "$FOUND" ]; then
    echo "matc was not in the Filament release archive" >&2
    exit 1
  fi
  if [ "$FOUND" != ".matc/matc" ]; then mv "$FOUND" .matc/matc; fi
fi

chmod +x .matc/matc
echo "matc: $(.matc/matc --version 2>&1 | head -1)"

for source in materials/*.mat; do
  name="$(basename "$source" .mat)"
  echo "== $source -> $OUT/$name.filamat"
  .matc/matc -p mobile -a all -o "$OUT/$name.filamat" "$source"
done

ls -l "$OUT"
