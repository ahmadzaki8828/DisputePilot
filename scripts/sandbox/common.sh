#!/usr/bin/env bash
# Shared helpers for the PayPal sandbox curl scripts.
#
# Reads PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET from the environment (or from a .env file at the repo
# root, if present). Never commit real credentials: .env is gitignored and .env.example holds names only.
#
# DisputePilot is sandbox-only. These scripts refuse to run against any host that is not
# *.sandbox.paypal.com, so a stray PAYPAL_BASE_URL cannot send a real merchant's dispute anywhere.

set -euo pipefail

PAYPAL_BASE_URL="${PAYPAL_BASE_URL:-https://api-m.sandbox.paypal.com}"

repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}

load_env() {
  local env_file
  env_file="$(repo_root)/.env"
  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
    PAYPAL_BASE_URL="${PAYPAL_BASE_URL:-https://api-m.sandbox.paypal.com}"
  fi
}

require_sandbox() {
  local host
  host="$(printf '%s' "$PAYPAL_BASE_URL" | sed -E 's#^https://([^/]+).*#\1#')"
  if [[ "$PAYPAL_BASE_URL" != https://* ]] || [[ "$host" != *.sandbox.paypal.com && "$host" != sandbox.paypal.com ]]; then
    echo "Refusing to run: PAYPAL_BASE_URL must be an https *.sandbox.paypal.com host (got '$PAYPAL_BASE_URL')." >&2
    echo "DisputePilot is sandbox-only; there is no live mode." >&2
    exit 2
  fi
}

require_credentials() {
  if [[ -z "${PAYPAL_CLIENT_ID:-}" || -z "${PAYPAL_CLIENT_SECRET:-}" ]]; then
    echo "PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET must be set (see .env.example)." >&2
    exit 2
  fi
}

# Prints a sandbox access token. Pass --quiet to suppress the scope/expiry note on stderr.
paypal_token() {
  load_env
  require_sandbox
  require_credentials
  local response token
  response="$(curl -sS -X POST "$PAYPAL_BASE_URL/v1/oauth2/token" \
    -u "$PAYPAL_CLIENT_ID:$PAYPAL_CLIENT_SECRET" \
    -H 'Accept: application/json' \
    -d 'grant_type=client_credentials')"
  token="$(printf '%s' "$response" | sed -nE 's/.*"access_token" *: *"([^"]+)".*/\1/p')"
  if [[ -z "$token" ]]; then
    echo "Could not get an access token. PayPal said:" >&2
    printf '%s\n' "$response" >&2
    exit 1
  fi
  printf '%s' "$token"
}

# paypal_call METHOD PATH [curl args...] - prints the response body and the HTTP status line on stderr.
paypal_call() {
  local method="$1" path="$2"
  shift 2
  local token status body response
  token="$(paypal_token)"
  response="$(curl -sS -w '\n%{http_code}' -X "$method" "$PAYPAL_BASE_URL$path" \
    -H "Authorization: Bearer $token" \
    -H 'Accept: application/json' \
    "$@")"
  status="$(printf '%s' "$response" | tail -n1)"
  body="$(printf '%s' "$response" | sed '$d')"
  echo "HTTP $status $method $path" >&2
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$body" | jq . 2>/dev/null || printf '%s\n' "$body"
  else
    printf '%s\n' "$body"
  fi
  # PayPal puts debug_id on every error; it is the first thing their support asks for.
  if [[ "$status" -ge 400 ]]; then
    printf '%s' "$body" | sed -nE 's/.*"debug_id" *: *"([^"]+)".*/debug_id: \1/p' >&2
    return 1
  fi
}
