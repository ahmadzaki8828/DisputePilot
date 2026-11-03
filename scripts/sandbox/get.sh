#!/usr/bin/env bash
# Shows one dispute in full. Usage: scripts/sandbox/get.sh <dispute_id>
#
# The links[] array in the response is what DisputePilot reads to decide which actions are allowed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <dispute_id>" >&2
  exit 2
fi
paypal_call GET "/v1/customer/disputes/$1"
