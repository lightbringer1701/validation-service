# Validation Service

Spring Cloud Stream service that validates OutcomingPayload.data against a versioned JSON Schema loaded from Model Registry.

Kafka: input pipeline.validation; success pipeline.outcoming; failure pipeline.failed. The original Kafka record key is preserved.

Local: Kafka localhost:9093, Model Registry http://localhost:3001, HTTP port 22010. Docker: Kafka kafka:9092, Model Registry http://model-registry:8080, port 8080.

Docs: docs/architecture.md, docs/configuration.md, docs/messaging.md, docs/testing.md.

Build: `./mvnw -B clean verify`.