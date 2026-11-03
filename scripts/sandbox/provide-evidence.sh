#!/usr/bin/env bash
# Submits proof of fulfilment for a dispute, the same multipart request the backend sends.
# Usage: scripts/sandbox/provide-evidence.sh <dispute_id> <tracking_number> [notes] [file]
#
# The JSON part is named "input"; the fictional carrier goes to PayPal as carrier_name OTHER plus the
# free-text carrier_name_other, so no real carrier's name appears anywhere.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <dispute_id> <tracking_number> [notes] [file]" >&2
  exit 2
fi
dispute_id="$1"
tracking="$2"
notes="${3:-Order shipped and delivered; carrier tracking confirms delivery.}"
file="${4:-}"

input="$(cat <<JSON
{"evidences":[{"evidence_type":"PROOF_OF_FULFILLMENT",
  "evidence_info":{"tracking_info":[{"carrier_name":"OTHER",
    "carrier_name_other":"Northline Courier","tracking_number":"$tracking"}]},
  "notes":"$notes"}]}
JSON
)"

args=(-F "input=$input;type=application/json")
if [[ -n "$file" ]]; then
  args+=(-F "file1=@$file")
fi
paypal_call POST "/v1/customer/disputes/$dispute_id/provide-evidence" "${args[@]}"
