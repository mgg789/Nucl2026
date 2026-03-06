# Hex.Team Mesh Messenger

Децентрализованный мессенджер для обмена сообщениями и файлами без обязательного интернета.
Проект создан под хакатонный сценарий: работа через P2P-соединения, ретрансляция через соседние узлы, шифрование контента и телеметрия состояния сети.

## Что умеет сейчас

- обнаружение узлов и установка P2P-сессий через Nearby Connections;
- двусторонний обмен сообщениями;
- мультихоп-пересылка с `ttl`/`hopCount`;
- дедупликация сообщений и защита от петель;
- очередь сообщений, `ACK`, retry и статусы доставки;
- протокол передачи файлов (meta + chunks + SHA-256);
- сборка входящего файла из чанков и проверка целостности (checksum);
- сохранение частично полученных файловых чанков и продолжение после перезапуска приложения;
- repair-запрос недостающих чанков (`FILE_REPAIR_REQUEST`) и дозагрузка пропусков;
- карта узлов (gossip topology);
- базовый call signaling (offer/answer/ice/end);
- антиспам rate-limit на входящие сообщения (защита от flood);
- UI в стиле Telegram/Figma с экранами Chats/Calls/Peers/Dev.

## Технологии

- Android + Kotlin + Jetpack Compose;
- Google Play Services Nearby (P2P транспорт);
- Kotlin Coroutines/Flow;
- Android Keystore (подпись сообщений);
- JSON-протокол поверх транспорта.

## Быстрый запуск

### Требования

- Android Studio актуальной версии;
- Android SDK 36;
- минимум 2 Android-устройства для демонстрации mesh;
- `adb` в `PATH`.

### Сборка

```bash
cd app
./gradlew :app:assembleDebug
```

### Установка на подключённые устройства

```bash
cd app
./scripts/build_and_install_connected.sh
```

## Демонстрационный сценарий (обязательная часть ТЗ)

1. На двух/трёх устройствах запустить приложение и выдать runtime-permissions.
2. На вкладке `Peers` убедиться, что узлы обнаружены и соединены.
3. Отправить текст в обе стороны и показать `ACK`/retry в `Dev`.
4. Показать мультихоп (через промежуточный узел) по логам и `hopCount`.
5. Отправить файл (кнопка вложения), показать прогресс чанков и checksum-валидность.
6. Показать сигналинг звонка (offer/call events) и сетевые метрики в `Dev`.

## Архитектура (кратко)

- `NearbyMeshClient` — discovery, соединения, приём/передача payload, ACK/retry, topology gossip.
- `SendOrchestrator` — очередь отправки и выбор транспорта по классу доставки.
- `MeshEnvelope` — унифицированный контейнер сообщения (id, ttl, policy, подпись).
- `PolicyEngine`/`PolicyKeyService` — правила доступа и ключи для шифрования контента.
- `FileTransferPlanner` — разбиение файлов на чанки + SHA-256.
- `MeshAccessScreen` — UI и сценарии демонстрации.

## Документация в репозитории

- `app/PROJECT_TECH_GUIDE_RU.md` — общий тех-гайд по проекту;
- `app/TECH_FLOW_ON_FINGERS_RU.md` — поток данных на простом языке;
- `app/P2P_CRYPTO_PLAYBOOK_RU.md` — практики по crypto/P2P;
- `app/CRYPTO_KEYS_EXPLAINED_RU.md` — пояснения по ключам;
- `app/KEYS_AND_ACCESS_OPTIONS_RU.md` — варианты схем доступа;
- `app/UNIVERSAL_SECURE_MESSENGER_BLUEPRINT_RU.md` — целевая архитектура продукта.

## Логи и метрики

- вкладка `Dev` показывает counters: `seen/dup/fwd/pendingAck/ackOk`, размер очереди, send/network logs;
- вкладка `Dev` показывает counters: `seen/dup/fwd/pendingAck/ackOk`, RTT `avg/p95`, loss%, размер очереди, send/network logs;
- вкладка `Dev` содержит fault-контролы для демонстрации отказов: `Relay ON/OFF`, `Loss 0/10/30%`, `Reset metrics`;
- технический отчёт lint: `app/app/build/reports/lint-results-debug.html`.

## Текущее ограничение

- полноценный медиаканал для real-time голоса/видео и продвинутая методика замеров jitter/loss находятся в активной доработке (сейчас реализован signaling-слой).
