# syntax=docker/dockerfile:1

# ------------------------------------------------------------------------------
# Stage 1: Build stage
# ------------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:resolve -B

# Copy source code and build jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ------------------------------------------------------------------------------
# Stage 2: Tester stage (used by backend-test service)
# ------------------------------------------------------------------------------
FROM builder AS tester

WORKDIR /app
CMD ["mvn", "test", "-B"]

# ------------------------------------------------------------------------------
# Stage 3: Runtime stage
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runner

RUN apk add --no-cache curl wget bash

WORKDIR /app

# Copy executable jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8080/api/v1/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
