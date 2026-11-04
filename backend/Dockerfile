# syntax=docker/dockerfile:1
# Build context is the REPOSITORY ROOT (the backend packages ../fixtures and ../evals into the jar):
#   docker build -f backend/Dockerfile .      (docker compose and render.yaml already do this)

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src/backend
COPY backend/pom.xml ./pom.xml
# Resolve dependencies in their own layer so source edits do not re-download them.
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY fixtures /src/fixtures
COPY evals/recordings /src/evals/recordings
COPY evals/report.json /src/evals/report.json
RUN mvn -B -q -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 disputepilot
WORKDIR /app
COPY --from=build /src/backend/target/disputepilot-backend-*-SNAPSHOT.jar /app/app.jar
USER disputepilot
EXPOSE 8080
# Zero-key defaults: simulated PayPal and recorded AI responses. Override through the environment (see .env.example).
ENV PAYPAL_MODE=mock \
    DP_LLM_MODE=recorded \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
