# Multi-stage: build the static SPA with Node, serve it with nginx.
# No secrets baked in — VITE_* build args are non-secret (07b slide 25); the runtime
# image is just static assets behind nginx.
FROM node:23-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Build-time public config (overridable at build): issuer, public client id, api base.
ARG VITE_KEYCLOAK_ISSUER=http://localhost:8081/realms/eurotransit
ARG VITE_KEYCLOAK_CLIENT_ID=eurotransit-frontend
ARG VITE_API_BASE=/api
ENV VITE_KEYCLOAK_ISSUER=$VITE_KEYCLOAK_ISSUER \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID \
    VITE_API_BASE=$VITE_API_BASE
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 8080
