# syntax=docker/dockerfile:1

# ------------------------------------------------------------------------------
# Stage 1: Build stage
# ------------------------------------------------------------------------------
FROM node:22-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
ARG VITE_ENABLE_DEMO_LOGIN=false
ENV VITE_ENABLE_DEMO_LOGIN=${VITE_ENABLE_DEMO_LOGIN}
RUN npm run build

# ------------------------------------------------------------------------------
# Stage 2: Tester stage (used by frontend-test service)
# ------------------------------------------------------------------------------
FROM builder AS tester

WORKDIR /app
CMD ["npm", "run", "test:ci"]

# ------------------------------------------------------------------------------
# Stage 3: Runtime stage
# ------------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runner

# R9-03: the official nginx image's entrypoint runs envsubst over every /etc/nginx/templates/*.template
# file at container start and writes the result into conf.d - this is the documented mechanism for
# injecting runtime (not build-time) config, since the CSP's MinIO origin can differ per deployment
# without rebuilding the image. NGINX_ENVSUBST_FILTER restricts substitution to exactly the variables
# this template references, so nginx's own $host/$remote_addr/$http_upgrade/etc. in nginx.conf.template
# are left untouched (envsubst would otherwise blank them out - they are not real environment variables).
ENV NGINX_ENVSUBST_FILTER="MINIO_PUBLIC_ORIGIN|REAL_IP_TRUSTED_PROXY|APP_PUBLIC_SCHEME|APP_PUBLIC_PORT|SECOND_BACKEND_UPSTREAM"
ENV REAL_IP_TRUSTED_PROXY=127.0.0.1
ENV APP_PUBLIC_SCHEME=http APP_PUBLIC_PORT=80 SECOND_BACKEND_UPSTREAM=""
# Default so `docker build` + `docker run` without an explicit MINIO_PUBLIC_ORIGIN still starts
# (matches the previous hardcoded default); infra/compose.yaml overrides this at runtime.
ENV MINIO_PUBLIC_ORIGIN="http://localhost:9000 https://localhost:9000"
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:80/ || exit 1

# R9-03: no explicit ENTRYPOINT/CMD override here - the base nginx:1.27-alpine image's own
# /docker-entrypoint.sh is what runs the /docker-entrypoint.d/*.sh scripts (including
# 20-envsubst-on-templates.sh, which renders our template above) before finally exec'ing
# `nginx -g "daemon off;"` itself. An explicit `ENTRYPOINT ["nginx", ...]` here would bypass that
# script entirely, silently falling back to nginx's own stock, un-templated default.conf baked into
# the base image (no CSP header at all) instead of ours.
