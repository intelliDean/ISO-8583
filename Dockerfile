# Multi-Stage Dockerfile for ISO 8583 Payment Protocol Engine
# ------------------------------------------------------------
# Stage 1: Build & Package
# ------------------------------------------------------------
FROM eclipse-temurin:26-jdk AS builder
WORKDIR /workspace

# Copy Maven wrapper & POM first for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build
COPY src/ src/
RUN ./mvnw clean package -DskipTests

# ------------------------------------------------------------
# Stage 2: Minimal Runtime Container
# ------------------------------------------------------------
FROM eclipse-temurin:26-jre-noble
WORKDIR /app

# Run as non-root user for enterprise container security
RUN addgroup --system iso && adduser --system --ingroup iso iso
USER iso:iso

# Copy artifact from builder
COPY --from=builder --chown=iso:iso /workspace/target/iso8583-*.jar /app/app.jar

# Expose HTTP API / Dashboard (8080) and Binary TCP Host Server (8583)
EXPOSE 8080 8583

# JVM Tuning for containerized workloads
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
