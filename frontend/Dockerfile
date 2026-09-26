# syntax=docker/dockerfile:1

# ------------------------------------------------------------------------------
# Stage 1: Build stage
# ------------------------------------------------------------------------------
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm install

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

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:80/ || exit 1

ENTRYPOINT ["nginx", "-g", "daemon off;"]
