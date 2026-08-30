# Multi-stage: build the environment-independent static SPA, then serve it with nginx.
FROM node:23-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Local development owns public/config/config.json. Production receives that file from
# a Kubernetes ConfigMap, so no environment-specific fallback is baked into the image.
RUN npm run build && rm -rf dist/config

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
RUN mkdir -p /usr/share/nginx/html/config
EXPOSE 8080
