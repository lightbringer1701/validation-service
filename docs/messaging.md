# Kafka messaging

| Binding | Topic | Direction |
|---|---|---|
| processing-in-0 | pipeline.validation | Consume |
| processing-out-0 | pipeline.outcoming | Success |
| failed-out-0 | pipeline.failed | Failure |

Input/success use shared OutcomingPayload; failure uses FailedPayload. The received key is explicitly copied to produced messages.