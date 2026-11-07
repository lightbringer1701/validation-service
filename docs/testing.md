# Testing

Run `./mvnw -B clean verify`. CI baseline on codex/ci-baseline: 18 tests passed.

Coverage includes schema processing, registry lookup/retry and stream-function behavior. Add Testcontainers tests for real Kafka key propagation, failed-topic delivery and broker failure semantics.