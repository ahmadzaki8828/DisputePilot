# DisputePilot developer shortcuts. Requires: Docker (for Postgres), Java 21, Node 22.
DP_ADMIN_TOKEN ?= dev-admin-token
API_URL ?= http://localhost:8080

.PHONY: dev test seed db

db:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db

# Runs Postgres, the backend (mock PayPal, recorded AI) and the web app. Ctrl-C stops the backend and web.
dev: db
	@trap 'kill 0' INT TERM; \
	(cd backend && PAYPAL_MODE=mock DP_ADMIN_TOKEN=$(DP_ADMIN_TOKEN) ./mvnw spring-boot:run) & \
	(cd web && DP_API_BASE_URL=$(API_URL) DP_ADMIN_TOKEN=$(DP_ADMIN_TOKEN) npm run dev) & \
	wait

test:
	cd backend && ./mvnw -B verify
	cd web && npm ci && npm run lint && npm test && npm run build

# Load the fixture disputes into the running backend (PAYPAL_MODE=mock only).
seed:
	curl -fsS -X POST -H "X-Admin-Token: $(DP_ADMIN_TOKEN)" $(API_URL)/api/dev/seed
	@echo
