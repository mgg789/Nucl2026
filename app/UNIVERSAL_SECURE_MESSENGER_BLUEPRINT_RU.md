# Universal Secure Messenger Blueprint (без привязки к платформам)

Цель: зафиксировать "как надо" для защищенного мессенджера уровня продукта, а не демо.

---

## 1) Нереалистичное требование и правильная формулировка

Тезис "100% без взлома" недостижим в реальности.  
Правильная цель:

- максимизировать стоимость атаки;
- минимизировать поверхность атаки;
- быстро обнаруживать инциденты;
- быстро отзывать/ротировать компрометированные ключи.

Это и есть инженерно корректный "высший эталон".

---

## 2) Обязательные продуктовые свойства

- E2E шифрование текстов, звонков и файлов.
- Forward secrecy + post-compromise security.
- Много транспортов с автопереключением.
- Низкая задержка для realtime.
- Надежная доставка (ACK/retry/qos/priority).
- Управление доступом (уровни/орг/роли).
- Ротация и отзыв ключей.
- Прозрачный аудит безопасности (без утечки контента).

---

## 3) Threat model (что защищаем и от кого)

## 3.1 Активы
- приватные ключи устройств;
- session/group ключи;
- контент сообщений/файлов/звонков;
- метаданные (кто, когда, с кем, сколько).

## 3.2 Типовые атакующие
- злоумышленник в той же сети;
- компрометированный клиент;
- insider с украденным устройством;
- MITM/relay attacker;
- массовый спамер/DoS.

## 3.3 Базовые требования к защите
- сообщение нельзя подделать;
- сообщение нельзя читать без права;
- replay не должен проходить;
- компрометация одного ключа не должна раскрывать всю историю;
- отзыв прав должен работать быстро и надежно.

---

## 4) Архитектура "как надо" (слоями)

## 4.1 Identity & Trust Layer
- корневой ключ организации (offline root).
- промежуточные ключи выдачи (online issuing CA).
- device credentials (пользователь + устройство + org + roles + expiry).
- QR provisioning для выдачи credential.
- CRL/OCSP-подобный механизм отзыва.

## 4.2 Crypto Protocol Layer
- 1:1: X3DH + Double Ratchet (Signal-подход).
- Groups: Sender Keys или MLS-подход.
- AEAD: AES-GCM или ChaCha20-Poly1305.
- KDF: HKDF.
- Anti-replay: message counters + window.
- Domain separation для разных типов ключей.

## 4.3 Transport Abstraction Layer
- транспортные адаптеры: Internet / LAN / Wi-Fi Direct / Bluetooth / relay.
- единый envelope и QoS-политика.
- маршрутизация по стоимости пути (latency/loss/battery).
- мультиканальная отправка (параллель или fallback).

## 4.4 Realtime Layer (calls)
- WebRTC (SRTP/DTLS).
- adaptive bitrate, jitter buffer, AEC/NS/AGC.
- ICE + STUN/TURN fallback.
- при недоступности realtime: voice notes fallback.

## 4.5 File Transfer Layer
- шифрование файла отдельным fileKey.
- chunking + parallel upload/download.
- resume по checkpoint.
- integrity hash (SHA-256/Blake3).
- дедупликация чанков (опционально).

## 4.6 Policy & Access Layer
- RBAC + ABAC (уровни/орг/роли/атрибуты).
- policy evaluation локально + policy signatures.
- доступ к контенту только криптографически (не только UI policy).

## 4.7 Observability & Security Ops
- telemetry без контента.
- security events stream.
- anomaly detection (массовые ретраи/подозрительные релеи).
- incident playbooks (отзыв, quarantine, forced rekey).

---

## 5) Ключевая схема шифрования для масштаба

## 5.1 Рекомендован гибрид
- массовые каналы: group/channel keys.
- чувствительные точечные: per-recipient key wrapping.

Почему:
- per-recipient в лоб дает O(N) рост metadata;
- group keys масштабируют трафик;
- гибрид дает баланс безопасности и производительности.

## 5.2 Управление ключами
- key versioning (`v1`, `v2`, ...).
- scheduled rekey + event-driven rekey (после отзыва).
- key epochs для групп.
- явный key provenance (кто и когда выдал).

---

## 6) Сетевая устойчивость и минимальная задержка

- Приоритизация: `calls > text > files`.
- Short path first + quality-aware routing.
- Fast reconnect + 0/1-RTT resume где возможно.
- локальные очереди + backpressure.
- ограничение flood/gossip TTL.
- адаптивные retry budgets.

---

## 7) Безопасность ключей устройства

- приватные ключи только в hardware-backed keystore (если доступно).
- device attestation (где поддерживается).
- биометрия/secure lock для локального unlock ключей.
- ключи never-exportable.
- отдельные ключи для signature/encryption.

---

## 8) Минимальный протоколный контракт (platform-agnostic)

Каждый пакет должен иметь:
- `messageId`, `conversationId`, `senderDeviceId`.
- `timestamp`, `counter`, `nonce`.
- `ciphertext`, `aad`.
- `signature` или authenticated sender key.
- `keyRef` (каким ключом шифровано).
- `transportHints` (priority/ttl/qos).

Для групп:
- `groupId`, `epoch`, `senderChainRef`.

---

## 9) DoD (Definition of Done) для "сделано правильно"

- Pen-test critical findings = 0.
- Replay/MITM tests проходят.
- Forced rekey и revoke работают end-to-end.
- 95p latency для текстов в целевой сети в пределах SLA.
- File resume работает при обрывах.
- Calls устойчивы при packet loss до целевого порога.
- Набор crypto test vectors green.
- Набор protocol conformance tests green.

---

## 10) План реализации по этапам

## Этап 0: Security foundation
- финализировать threat model;
- выбрать криптобиблиотеки и протокол;
- внедрить test vectors и property-based tests.

## Этап 1: E2E text stable
- identity provisioning + cert chain;
- 1:1 E2E;
- delivery reliability + qos.

## Этап 2: Group security
- group keys/epochs;
- revoke/rekey flows;
- secure admin tooling.

## Этап 3: Multi-transport mesh
- transport abstraction + route scoring;
- gossip/topology + safe limits.

## Этап 4: Calls + files
- WebRTC calls;
- chunked encrypted files + resume.

## Этап 5: Hardening
- external audit;
- red-team drills;
- incident response automation.

---

## 11) Что запрещено делать в production

- общий hardcoded content key.
- только логический доступ без крипто-ограничения.
- plaintext в логах.
- отсутствие revoke/rekey.
- неподписанные control messages.
- неограниченный flood/gossip без rate limiting.

---

## 12) Что можно показать на защите как "уровень продукта"

- архитектура с четким разделением слоев;
- реальные сценарии отказоустойчивости;
- политика безопасности и ротации;
- измеримые SLO по задержке и доставке;
- roadmap к audit-ready production.

Этот blueprint — единый ориентир "как надо" для команды и дальнейшей реализации.

