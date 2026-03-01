# ============================================
# Multi-stage build: Maintenance Service
# ============================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY . .
RUN ./sbt assembly 2>/dev/null || sbt assembly

# ============================================
# Runtime
# ============================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/scala-3.4.0/maintenance-service-assembly-0.1.0.jar app.jar

EXPOSE 8087

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8087/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
