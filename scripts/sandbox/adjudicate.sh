#!/usr/bin/env bash
# Sandbox-only: decides a dispute that is UNDER_REVIEW.
# Usage: scripts/sandbox/adjudicate.sh <dispute_id> SELLER_FAVOR|BUYER_FAVOR
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <dispute_id> SELLER_FAVOR|BUYER_FAVOR" >&2
  exit 2
fi
case "$2" in
  SELLER_FAVOR|BUYER_FAVOR) ;;
  *) echo "Outcome must be SELLER_FAVOR or BUYER_FAVOR." >&2; exit 2 ;;
esac
paypal_call POST "/v1/customer/disputes/$1/adjudicate" \
  -H 'Content-Type: application/json' \
  -d "{\"adjudication_outcome\":\"$2\"}"
