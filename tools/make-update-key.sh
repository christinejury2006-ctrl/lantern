#!/usr/bin/env bash
#
# One-time setup: create the signing key that lets a newly downloaded Lore APK update the
# copy already on your phone, the way a Play Store update does.
#
# Run this once, from the repository root:
#     ./tools/make-update-key.sh
#
# It creates a keystore, then stores it and its passwords as GitHub Actions secrets.
# Keep the keystore file and its password somewhere safe and private:
# if you lose them you cannot update an installed Lore again without an uninstall.
set -euo pipefail

REPO="${1:-christinejury2006-ctrl/lantern}"
KEYSTORE="${LORE_KEYSTORE_FILE:-lore-update.keystore}"
ALIAS="lore-update"

command -v keytool >/dev/null 2>&1 || {
  echo "keytool not found. Install a JDK — Android Studio ships one, or use its embedded JDK." >&2
  exit 1
}
command -v gh >/dev/null 2>&1 || {
  echo "The GitHub CLI (gh) is required so the key can be stored as secrets." >&2
  exit 1
}
command -v base64 >/dev/null 2>&1 || {
  echo "base64 not found." >&2
  exit 1
}

if [ -e "$KEYSTORE" ]; then
  echo "A keystore already exists at $KEYSTORE — refusing to overwrite it."
  echo "Overwriting would change the app signature and break future updates."
  exit 1
fi

STORE_PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"

echo "Creating $KEYSTORE ..."
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12 \
  -storepass "$STORE_PASS" \
  -keypass "$STORE_PASS" \
  -dname "CN=Lore Update, OU=Lore, O=Lore, C=NG"

echo "Uploading secrets to $REPO ..."
base64 < "$KEYSTORE" | tr -d '\n' | gh secret set LORE_KEYSTORE_BASE64 --repo "$REPO"
gh secret set LORE_KEYSTORE_PASSWORD --repo "$REPO" --body "$STORE_PASS"
gh secret set LORE_KEY_ALIAS --repo "$REPO" --body "$ALIAS"
gh secret set LORE_KEY_PASSWORD --repo "$REPO" --body "$STORE_PASS"

cat <<INFO

Done. Back these up now, both are required for every future update:

  keystore : $KEYSTORE   (keep it private; it is gitignored)
  password : $STORE_PASS

Next:
  1. Install one more APK the old way (uninstall the current app first — the signature
     is changing one last time).
  2. From then on, every newer APK installs straight over the top and keeps your library.
INFO
