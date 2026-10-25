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

# The output is named after the material's declared name, not after the file it came from.
# Filament looks a material up by name, so the two drifting apart means the app asks for an
# asset that was never written and quietly falls back to compiling on the phone — the very
# thing this step exists to remove.
for source in materials/*.mat; do
  name="$(sed -n 's/^[[:space:]]*name[[:space:]]*:[[:space:]]*\([A-Za-z0-9_]*\).*/\1/p' "$source" | head -1)"
  if [ -z "$name" ]; then
    echo "no material name declared in $source" >&2
    exit 1
  fi
  echo "== $source -> $OUT/$name.filamat"
  .matc/matc -p mobile -a all -o "$OUT/$name.filamat" "$source"
  test -s "$OUT/$name.filamat" || { echo "matc wrote nothing for $source" >&2; exit 1; }
  echo "   material '$name', $(wc -c < "$OUT/$name.filamat") bytes"
done

ls -l "$OUT"
