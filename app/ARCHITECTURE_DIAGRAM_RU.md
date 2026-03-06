# Архитектурная схема

```mermaid
flowchart TD
    UI["Compose UI\nMeshAccessScreen"] --> ORCH["SendOrchestrator\nочередь + выбор транспорта"]
    ORCH --> TA["TransportAdapter (Nearby/Stub)"]
    TA --> NM["NearbyMeshClient\nDiscovery/Conn/Payload"]
    NM --> ENV["MeshEnvelope\nid + ttl + policy + signature"]
    NM --> ACK["ACK + Retry + Dedup"]
    NM --> TOP["Topology Gossip\nnodes/edges"]

    ENV --> CR["MeshCrypto + MeshSignature"]
    CR --> KEY["PolicyKeyService\norg/level keyId"]
    CR --> TOFU["IdentityTrustStore\nTOFU + anti-spoofing"]

    ORCH --> FILE["FileTransferPlanner\nmeta/chunks/SHA-256"]
    NM --> FILEASM["Incoming file assembler\nchunk progress + checksum verify"]

    NM --> DEV["Dev metrics/logs\nseen/dup/fwd/ack/queue"]
```

## Поведение при разрывах

- при потере соединения endpoint удаляется из active set;
- неподтверждённые сообщения остаются в очереди;
- retry отправки продолжается, пока не достигнут лимит;
- после восстановления соединения `flushAll()` дозапускает pending очередь.

## Маршрутизация и мультихоп

- каждое сообщение имеет `ttl` и `hopCount`;
- ретрансляция идёт всем peers, кроме источника текущего шага;
- `seenMessageIds` блокирует дубли и петли.
