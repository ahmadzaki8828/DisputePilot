#!/usr/bin/env bash
# Lists sandbox disputes. Usage: scripts/sandbox/list.sh [dispute_state] [page_size]
# e.g. scripts/sandbox/list.sh REQUIRED_ACTION 10
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

state="${1:-}"
page_size="${2:-20}"
path="/v1/customer/disputes?page_size=$page_size"
if [[ -n "$state" ]]; then
  path="$path&dispute_state=$state"
fi
paypal_call GET "$path"
