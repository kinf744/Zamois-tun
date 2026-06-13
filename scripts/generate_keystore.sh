#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
# generate_keystore.sh
# Generates a release keystore and encodes it for GitHub Actions secrets.
#
# Usage: bash scripts/generate_keystore.sh
# ──────────────────────────────────────────────────────────────────────────────

set -e

KEY_ALIAS="kighmu_key"
KEYSTORE_FILE="kighmu_release.keystore"
KEYSTORE_PASS="kighmu_store_$(date +%s | sha256sum | head -c 16)"
KEY_PASS="kighmu_key_$(date +%s | sha256sum | head -c 16)"
VALIDITY_DAYS=10000

echo "═══════════════════════════════════════"
echo "  KIGHMU VPN - Keystore Generator"
echo "═══════════════════════════════════════"
echo ""

# Generate keystore
echo "Generating keystore..."
keytool -genkey -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$KEYSTORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=KIGHMU VPN, OU=Mobile, O=KIGHMU, L=Unknown, ST=Unknown, C=US" \
  2>/dev/null

# Encode to base64
BASE64_KEYSTORE=$(base64 -w 0 "$KEYSTORE_FILE")

echo ""
echo "═══════════════════════════════════════"
echo "  Add these as GitHub Secrets:"
echo "  Settings → Secrets → Actions → New"
echo "═══════════════════════════════════════"
echo ""
echo "Secret name: KEYSTORE_BASE64"
echo "Secret value:"
echo "$BASE64_KEYSTORE"
echo ""
echo "Secret name: KEY_ALIAS"
echo "Secret value: $KEY_ALIAS"
echo ""
echo "Secret name: KEYSTORE_PASSWORD"
echo "Secret value: $KEYSTORE_PASS"
echo ""
echo "Secret name: KEY_PASSWORD"
echo "Secret value: $KEY_PASS"
echo ""
echo "═══════════════════════════════════════"
echo "  IMPORTANT: Save these values safely!"
echo "  Keystore file: $KEYSTORE_FILE"
echo "═══════════════════════════════════════"

# Save credentials to a local file (DO NOT COMMIT)
cat > keystore_credentials.txt << CREDS
KEYSTORE_FILE=$KEYSTORE_FILE
KEY_ALIAS=$KEY_ALIAS
KEYSTORE_PASSWORD=$KEYSTORE_PASS
KEY_PASSWORD=$KEY_PASS
CREDS

echo ""
echo "Credentials saved to: keystore_credentials.txt"
echo "⚠️  Add keystore_credentials.txt to .gitignore!"
