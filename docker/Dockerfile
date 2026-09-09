# Multi-stage Docker build for OpenLR WebTool (PostgreSQL only)
#
# Build and runtime JDKs are deliberately the same major version. They used to be
# 17 and 21 respectively -- harmless given a jvmTarget of 17, but unintentional.
# Stage 1: Build the application
FROM gradle:8.5-jdk17 AS builder

WORKDIR /build

# Copy build files
COPY settings.gradle.kts ./
COPY main/build.gradle.kts ./main/

# Copy source code
COPY main/src ./main/src

# Build the application
RUN gradle :main:bootJar --no-daemon

# Stage 2: Runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install curl for health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the built JAR
COPY --from=builder /build/main/build/libs/*.jar app.jar

# Expose port
EXPOSE 8081

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s \
  CMD curl -f http://localhost:8081/api/v1/health || exit 1

# Environment variables
ENV SPRING_PROFILES_ACTIVE=generic_pg_mapdb
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]