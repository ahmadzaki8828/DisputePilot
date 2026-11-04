# syntax=docker/dockerfile:1
# Build context is ./web  (docker build -f web/Dockerfile web)

# ---- deps ----
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---- run ----
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
# next.config.ts sets output: "standalone": a self-contained server plus the static assets next to it.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
RUN addgroup -S dp && adduser -S dp -G dp && chown -R dp:dp /app
USER dp
EXPOSE 3000
# DP_API_BASE_URL and DP_ADMIN_TOKEN are read at request time (server-side only; the token never reaches the browser).
CMD ["node", "server.js"]
