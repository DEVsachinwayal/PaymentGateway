#!/usr/bin/env bash
# Generates an RSA-2048 key pair for RS256 access tokens and appends JWT_PRIVATE_KEY / JWT_PUBLIC_KEY to .env (git-ignored).
# Nothing is printed. Existing values are never overwritten. Needs openssl.
#   JWT_PRIVATE_KEY -> auth-service only (base64 PKCS#8)      JWT_PUBLIC_KEY -> api-gateway (base64 X.509, comma separated while rotating)
# Rotation: generate a new pair, deploy the gateway with "<old>,<new>" in JWT_PUBLIC_KEY, switch auth-service to the new private key,
# wait one access-token lifetime (15 min) and drop the old public key.
set -eu
cd "$(dirname "$0")/.."
ENV_FILE="${1:-.env}"
touch "$ENV_FILE"
if grep -q '^JWT_PRIVATE_KEY=.\+' "$ENV_FILE" || grep -q '^JWT_PUBLIC_KEY=.\+' "$ENV_FILE"; then
  echo "JWT_PRIVATE_KEY / JWT_PUBLIC_KEY already present in $ENV_FILE - not touching them" >&2
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$TMP/priv.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$TMP/priv.pem" -outform DER -out "$TMP/priv.der" 2>/dev/null
openssl pkey -in "$TMP/priv.pem" -pubout -outform DER -out "$TMP/pub.der" 2>/dev/null
{
  echo "JWT_PRIVATE_KEY=$(openssl base64 -A -in "$TMP/priv.der")"
  echo "JWT_PUBLIC_KEY=$(openssl base64 -A -in "$TMP/pub.der")"
} >> "$ENV_FILE"
echo "RS256 key pair written to $ENV_FILE (values not shown)" >&2
