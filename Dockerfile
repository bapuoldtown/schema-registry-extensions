# ============================================================================
# Custom Schema Registry image with block-delete extension baked in.
#
# Multi-stage build:
#   Stage 1 (builder)  — compiles the JAR with Maven
#   Stage 2 (runtime)  — extends Confluent's SR image, copies JAR in
#
# Published as ghcr.io/<org>/sr-block-delete:<tag> by GitHub Actions.
# Consumers (docker compose, EKS Helm charts) just reference the image.
# ============================================================================

# ---- Stage 1: Build the JAR ----
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build
# Copy POM first so dependency layer caches between runs
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy source and test code, compile + test + package
COPY src ./src
RUN mvn -B -q verify

# ---- Stage 2: Runtime image (extends official Confluent SR) ----
FROM confluentinc/cp-schema-registry:7.6.1

# Standard Confluent convention: additional JARs go under /usr/share/java
# The subdirectory keeps our extension isolated and auditable.
COPY --from=builder /build/target/block-delete-extension-1.0.0.jar \
     /usr/share/java/kop-extensions/block-delete-extension.jar

# Tell the SR launcher to include our JAR on the classpath.
# kafka-run-class.sh prepends $CLASSPATH to the default, so our extension
# class is available for reflective instantiation at startup.
ENV CLASSPATH="/usr/share/java/kop-extensions/*"

# Auto-activate the extension — no per-deployment config needed.
# Override with SCHEMA_REGISTRY_RESOURCE_EXTENSION_CLASS="" to disable.
ENV SCHEMA_REGISTRY_RESOURCE_EXTENSION_CLASS="com.example.schemaregistry.BlockDeleteExtension"

# Image metadata (OCI standard labels — shows up in `docker inspect`)
LABEL org.opencontainers.image.title="schema-registry-block-delete"
LABEL org.opencontainers.image.description="Confluent Schema Registry with DELETE-blocking extension"
LABEL org.opencontainers.image.source="https://github.com/OWNER/REPO"
LABEL org.opencontainers.image.licenses="MIT"
