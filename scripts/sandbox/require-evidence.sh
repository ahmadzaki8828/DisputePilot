#!/usr/bin/env bash
# Sandbox-only: asks PayPal to require seller evidence, moving an UNDER_REVIEW dispute back to
# WAITING_FOR_SELLER_RESPONSE so provide-evidence becomes available again.
# Usage: scripts/sandbox/require-evidence.sh <dispute_id> [SELLER_EVIDENCE]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <dispute_id> [SELLER_EVIDENCE]" >&2
  exit 2
fi
action="${2:-SELLER_EVIDENCE}"
paypal_call POST "/v1/customer/disputes/$1/require-evidence" \
  -H 'Content-Type: application/json' \
  -d "{\"action\":\"$action\"}"
