#!/usr/bin/env bash
#
# Attach a registered Express Module to a link's module flow using the public
# RTB Fabric API (UpdateLinkModuleFlow).
#
# Prerequisites:
#   - The module is already registered in the RTB Fabric catalog (see
#     docs/module-contract.md — registration is not self-service today).
#   - You have a gateway and a link, and AWS credentials for the account that owns them.
#
# Usage:
#   GATEWAY_ID=... LINK_ID=... ./attach-module.sh
#
set -euo pipefail

: "${GATEWAY_ID:?set GATEWAY_ID}"
: "${LINK_ID:?set LINK_ID}"
: "${AWS_REGION:=us-east-1}"

# NOTE: the exact module-flow payload for a custom module depends on the public
# module-authoring API. Re-verify the field names against the shipped public SDK
# before relying on this. The attach operation itself (update-link-module-flow) is
# a public RTB Fabric API.
aws rtbfabric update-link-module-flow \
  --region "$AWS_REGION" \
  --gateway-id "$GATEWAY_ID" \
  --link-id "$LINK_ID" \
  --modules file://module-flow.json

echo "Attached module flow to link $LINK_ID on gateway $GATEWAY_ID"
