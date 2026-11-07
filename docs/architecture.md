# Architecture

The processing Spring Cloud Stream function consumes an OutcomingPayload, retrieves schema by schemaId and schemaVersion, and validates data with NetworkNT JSON Schema Validator.

On success it forwards the original payload to pipeline.outcoming. On failure it emits FailedPayload to pipeline.failed. Both paths preserve the Kafka record key.

Schema lookup uses Model Registry, Caffeine caching and retry handling. MDC is populated with traceId and schemaId during processing.