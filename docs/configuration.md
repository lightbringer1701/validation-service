# Configuration

| Setting | Local | Docker |
|---|---|---|
| Kafka | localhost:9093 | kafka:9092 |
| Model Registry | http://localhost:3001 | http://model-registry:8080 |
| HTTP port | 22010 | 8080 |

Build requires FLEXI_COMMON_TOKEN with GitHub Packages read:packages access.

## Local Model Registry target

Local URL localhost:3001 targets the committed Mockoon environment in flexi-mockoon. The real Model Registry process uses local HTTP port 30001; set the client URL to localhost:30001 when testing against the real service.
