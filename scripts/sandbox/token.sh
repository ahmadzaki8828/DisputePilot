#!/usr/bin/env bash
# Prints a PayPal sandbox access token. Usage: scripts/sandbox/token.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
paypal_token
echo
