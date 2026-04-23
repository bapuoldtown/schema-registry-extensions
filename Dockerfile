# Multi-stage Dockerfile — accepts CONFLUENT_VERSION build arg so the
# matrix CI can build images for multiple SR versions without editing files.

ARG CONFLUENT_VERSION=7.9.2

# Stage 1: build the JAR with Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
ARG CONFLUENT_VERSION
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B verify -Dconfluent.version=${CONFLUENT_VERSION}

# Stage 2: copy the JAR into a real Schema Registry image
FROM confluentinc/cp-schema-registry:${CONFLUENT_VERSION}
ARG CONFLUENT_VERSION
LABEL org.opencontainers.image.title="schema-registry-with-block-delete"
LABEL org.opencontainers.image.description="Confluent SR ${CONFLUENT_VERSION} with block-delete extension"
LABEL confluent.version="${CONFLUENT_VERSION}"

USER root
RUN mkdir -p /usr/share/java/kop-extensions
COPY --from=builder /build/target/block-delete-extension-*.jar /usr/share/java/kop-extensions/block-delete-extension.jar
RUN chown -R appuser:appuser /usr/share/java/kop-extensions
USER appuser

ENV CLASSPATH="/usr/share/java/kop-extensions/*"
ENV SCHEMA_REGISTRY_RESOURCE_EXTENSION_CLASS="com.example.schemaregistry.BlockDeleteExtension"