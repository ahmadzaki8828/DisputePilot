# DisputePilot developer shortcuts. Requires: Docker (for Postgres), Java 21, Node 22.
DP_ADMIN_TOKEN ?= dev-admin-token
API_URL ?= http://localhost:8080

.PHONY: dev app test seed demo db

db:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db

# Runs Postgres, the backend (mock PayPal, recorded AI) and the web app. Ctrl-C stops the backend and web.
dev: db app

# The backend and the web app only, against a Postgres you already run on localhost:5432 (database, user and
# password "disputepilot"). The six starter disputes and their recorded drafts load into an empty database.
app:
	@test -d web/node_modules || (cd web && npm ci)
	@trap 'kill 0' INT TERM; \
	(cd backend && PAYPAL_MODE=mock DP_DEMO_SEED_ON_START=true DP_ADMIN_TOKEN=$(DP_ADMIN_TOKEN) ./mvnw spring-boot:run) & \
	(cd web && DP_API_BASE_URL=$(API_URL) DP_ADMIN_TOKEN=$(DP_ADMIN_TOKEN) npm run dev) & \
	wait

test:
	cd backend && ./mvnw -B verify
	cd web && npm ci && npm run lint && npm test && npm run build

# Load the fixture disputes into the running backend (PAYPAL_MODE=mock only).
seed:
	curl -fsS -X POST -H "X-Admin-Token: $(DP_ADMIN_TOKEN)" $(API_URL)/api/dev/seed
	@echo

# The demo-video path against a running backend: reset to the starter set, then simulate four disputes through
# the webhook pipeline (item not received -> fight, not as described -> offer, unauthorised -> accept, and a
# prompt-injection attempt).
demo:
	curl -fsS -X POST -H "X-Admin-Token: $(DP_ADMIN_TOKEN)" $(API_URL)/api/demo/reset
	@echo
	@for t in ITEM_NOT_RECEIVED NOT_AS_DESCRIBED UNAUTHORISED PROMPT_INJECTION; do \
	  curl -fsS -X POST -H "X-Admin-Token: $(DP_ADMIN_TOKEN)" -H "Content-Type: application/json" \
	    -d "{\"template\":\"$$t\"}" $(API_URL)/api/demo/disputes > /dev/null && echo "simulated $$t"; \
	done
