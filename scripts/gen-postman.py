#!/usr/bin/env python3
"""Generates docs/postman/DisputePilot.postman_collection.json from contracts/openapi.yaml.

    python3 scripts/gen-postman.py

Needs PyYAML (pip install pyyaml). Variables: baseUrl (default http://localhost:8080), adminToken (the value of
DP_ADMIN_TOKEN; docker compose uses local-demo-admin-token), disputeId, draftId.
"""
import json
import re
import uuid
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())

BODIES = {
    "createDemoDispute": {"template": "ITEM_NOT_RECEIVED"},
    "linkTransaction": {"sellerTransactionId": "TXN-FROM-PAYPAL-SANDBOX", "orderId": "ORD-1042"},
    "editDraft": {"notes": "Edited note to PayPal."},
    "approveDraft": {"overrideValidation": False},
}
PATH_VARS = {"id": None}


def path_with_vars(path, op):
    def repl(m):
        name = m.group(1)
        if name != "id":
            return "{{" + name + "}}"
        return "{{draftId}}" if "/drafts/{id}" in path and not path.startswith("/api/disputes") else "{{disputeId}}"
    return re.sub(r"\{(\w+)\}", repl, path)


def request(path, method, op):
    headers = [{"key": "X-Admin-Token", "value": "{{adminToken}}"}] if path.startswith("/api") else []
    if op.get("operationId") == "submitDraft":
        headers.append({"key": "Idempotency-Key", "value": "{{$guid}}"})
    url_path = path_with_vars(path, op)
    query = []
    for p in op.get("parameters", []):
        if p.get("in") == "query":
            query.append({"key": p["name"], "value": "", "disabled": True})
    item = {
        "name": f"{method.upper()} {path}",
        "request": {
            "method": method.upper(),
            "header": headers,
            "url": {
                "raw": "{{baseUrl}}" + url_path,
                "host": ["{{baseUrl}}"],
                "path": [s for s in url_path.split("/") if s],
                "query": query,
            },
            "description": op.get("summary", ""),
        },
    }
    body = BODIES.get(op.get("operationId"))
    if body is not None:
        item["request"]["header"].append({"key": "Content-Type", "value": "application/json"})
        item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2)}
    return item


folders = {}
for path, ops in spec["paths"].items():
    for method, op in ops.items():
        if method not in ("get", "post", "patch", "put", "delete"):
            continue
        tag = (op.get("tags") or ["Other"])[0]
        folders.setdefault(tag, []).append(request(path, method, op))

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "disputepilot")),
        "name": "DisputePilot API",
        "description": "Generated from contracts/openapi.yaml by scripts/gen-postman.py. "
                       "Set adminToken to your DP_ADMIN_TOKEN; with docker compose it is local-demo-admin-token.",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "variable": [
        {"key": "baseUrl", "value": "http://localhost:8080"},
        {"key": "adminToken", "value": "local-demo-admin-token"},
        {"key": "disputeId", "value": ""},
        {"key": "draftId", "value": ""},
    ],
    "item": [{"name": tag, "item": items} for tag, items in folders.items()],
}
out = ROOT / "docs/postman/DisputePilot.postman_collection.json"
out.write_text(json.dumps(collection, indent=2) + "\n")
print(f"wrote {out.relative_to(ROOT)}: {sum(len(v) for v in folders.values())} requests in {len(folders)} folders")
