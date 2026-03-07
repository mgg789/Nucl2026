# PeerDone: архитектура и сравнение с эталоном (sowmen)

## Как устроен PeerDone

### Транспорт
- **Google Nearby Connections** (BLE + Wi‑Fi), стратегия `P2P_CLUSTER`: устройства сами находят друг друга и устанавливают соединения. **Одна общая Wi‑Fi сеть и интернет не нужны** — это полностью офлайн P2P (обнаружение и связь идут напрямую между устройствами, при необходимости через Wi‑Fi в P2P-режиме). Один экземпляр `NearbyMeshClient` в `PeerDoneApplication`, доступ через `LocalNearbyClient`.
- Сообщения: подпись (DeviceKeyStoreSigner), шифрование (MeshCrypto, PolicyKeyService), конверт `MeshEnvelope` (CHAT / ACK / TOPOLOGY). В теле CHAT — JSON от `ContentCodec` (Text, FileMeta, FileChunk, CallSignal, AudioPacket и т.д.).

### Отправка (mesh-модель)
- **broadcast(sender, content)** — кодирует контент, собирает конверт, вызывает **sendChat** и шлёт **всем** из `connected` (мультихоп через посредников). Используется для чата, файлов и сигналов звонков.
- **sendToPeer(peerUserId, sender, content)** — опционально, шлёт только одному пиру; для совместимости с mesh всё основное идёт через broadcast.

### Звонки (WebRTC + mesh-сигналинг)
- **CallManager** (один на приложение): инициация звонка, приём offer/answer/ice/end.
- **WebRtcCallSession**: сигналинг (SDP offer/answer, ICE candidates) передаётся через mesh (broadcast), медиа — напрямую через **WebRTC** (PeerConnection, io.getstream:stream-webrtc-android). Так по ТЗ минимизируются задержка и джиттер.
- При отсутствии SDP в сообщении (старые клиенты) используется fallback: кастомный аудио-стрим (AudioCaptureManager/AudioPlaybackManager) через mesh.
- Входящие сообщения обрабатываются в **CallSignalHandler** (в корне приложения).

### Чаты и файлы
- Текстовые сообщения и файлы (meta + chunks) отправляются через **SendOrchestrator** и **broadcast** (всем подключённым). В чате сообщения фильтруются по `envelope.senderUserId == peerId`.
- **Передача файлов (по ТЗ)**: чанки, SHA-256 и контроль целостности, запрос недостающих чанков (FileRepairRequest), повторная отправка. Собранный файл сохраняется в `files/received/`, в сообщении передаётся `receivedFilePath`/`receivedFileName`; в UI — тип FILE и кнопка «Открыть» (через FileProvider).

---

## Эталон: [sowmen/Android-P2P-Chat-Messenger-using-Java-TCP-IP-Socket-Programming](https://github.com/sowmen/Android-P2P-Chat-Messenger-using-Java-TCP-IP-Socket-Programming)

### Транспорт
- **TCP-сокеты** по Wi‑Fi: один узел поднимает `ServerSocket`, второй подключается как `Socket`. Соединение 1:1, в одной сети.

### Сообщения
- Один класс **Message** (Serializable): `id`, `text`, `user`, `createdAt`, опционально `filename` и `file` (byte[]). Текст и файл в одном объекте.
- **SendMessage**: на каждое сообщение открывается новый `Socket`, в него пишется `ObjectOutputStream.writeObject(message)`, затем сокет закрывается.
- **MessageReceiveServer**: в фоне слушает `ServerSocket.accept()`, на каждое подключение читает один `Message` через `ObjectInputStream.readObject()` и передаёт в UI.

### Отличия от PeerDone
| Аспект        | sowmen (эталон)        | PeerDone                          |
|---------------|------------------------|-----------------------------------|
| Транспорт     | TCP, 1:1               | Nearby, mesh (много пиров)        |
| Подключение   | IP:port, QR            | Авто-обнаружение Nearby           |
| Модель сообщ. | Один Message + byte[]  | MeshEnvelope + ContentCodec типы  |
| Кому шлём     | Один сокет (получатель)| broadcast или sendToPeer(peerId)  |
| Файлы         | В теле Message         | FileMeta + FileChunk по mesh      |
| Звонки        | Нет                    | CallSignal + AudioPacket          |

---

## Что взято из эталона и что изменено в PeerDone

1. **Идея 1:1 для диалога**  
   В эталоне каждое сообщение идёт одному получателю. В PeerDone для **звонков и аудио** добавлена **sendToPeer(peerId, ...)** — сигналы и аудио шлём только тому пиру, с которым идёт звонок, а не всем в mesh.

2. **Простая модель сообщения**  
   В эталоне один тип Message с текстом и опциональным файлом. У нас типы контента разделены (Text, FileMeta, FileChunk, CallSignal, AudioPacket), но принцип «одно сообщение — один получатель» для звонка соблюдаем через sendToPeer.

3. **Надёжная доставка**  
   В эталоне отправка по сокету «доставил или исключение». У нас для CHAT есть ACK и retry в sendChat; для звонков отправка без очереди/retry, как один пакет.

---

## Совместимость iOS и Android

Сейчас транспорт — **Google Nearby Connections** (только Android). Чтобы связать iOS и Android в одном mesh, нужен **общий транспорт**, который есть на обеих платформах.

### Варианты

1. **BLE (Bluetooth Low Energy)** — единственный вариант «из коробки» для офлайн P2P между iPhone и Android без интернета и без общей Wi‑Fi сети.
   - **iOS**: Core Bluetooth (CBCentralManager, CBPeripheralManager, GATT).
   - **Android**: BluetoothLeAdapter, GATT server/client (стандартный API).
   - **Идея**: один и тот же **прикладной протокол** (формат конвертов, типы контента, шифрование) поверх BLE на обеих платформах. Обнаружение — через BLE advertising/scanning, обмен — через GATT characteristics (чтение/запись/уведомления).
   - Примеры: BitChat, BLEMeshChat — BLE mesh-чат с ретрансляцией между узлами, один протокол для iOS и Android.

2. **Общая Wi‑Fi сеть** (если она есть).
   - На iOS и Android можно поднять локальный сервер (TCP/UDP) и искать друг друга по mDNS/Bonjour. Тогда транспорт — сокеты по LAN, протокол (MeshEnvelope, ContentCodec) остаётся тем же. Минус: нужна одна сеть.

3. **Kotlin Multiplatform (KMP)** для общей логики.
   - Общий код: протокол (кодирование конвертов, типы сообщений, крипто), возможно бизнес-логика. Транспорт — отдельные реализации: на Android — Nearby или BLE, на iOS — только BLE (и при наличии — LAN). Библиотеки вроде **Blue-Falcon** дают единый BLE API для Android и iOS в одном KMP-модуле.

### Рекомендуемый путь для PeerDone

- **Оставить Nearby на Android** для связи Android–Android (высокая пропускная способность, уже работает).
- **Добавить BLE-транспорт** с тем же прикладным протоколом (MeshEnvelope, ContentCodec, подпись/шифрование):
  - на Android — второй канал (BLE GATT поверх своего сервиса/характеристик);
  - на iOS — единственный канал (Core Bluetooth).
- **Единый формат сообщений**: один и тот же конверт и типы контента на обеих платформах, чтобы Android с BLE и iOS с BLE понимали друг друга. Тогда совместимость iOS–Android обеспечивается общим протоколом поверх BLE, без изменения формата чата/звонков/файлов.

Итог: связь iOS и Android достигается **общим протоколом поверх BLE**; при необходимости общую часть (протокол, крипто) можно вынести в KMP и переиспользовать в нативном iOS-клиенте.

### WiFi Direct (Android): обнаружение без Bluetooth

**WiFi Direct не требует предварительного подключения по Bluetooth.** Обнаружение устройств идёт через собственный механизм: `WifiP2pManager.discoverPeers()`. После вызова система рассылает запросы по Wi‑Fi P2P и при обнаружении соседей присылает `WIFI_P2P_PEERS_CHANGED_ACTION`. Подключение затем выполняется через `connect()` к выбранному пиру. На части устройств и версий Android обнаружение может быть нестабильным (задержки, пустой список), поэтому в приложении добавлен выбор протокола: Nearby (по умолчанию) или WiFi Direct.

---

## Текущее поведение (кратко)

- **Чаты**: сообщения и файлы идут через broadcast; в чате показываются только сообщения с `senderUserId == peerId`.
- **Звонки**: offer/answer/end и аудио идут через **sendToPeer(peerId, ...)** только второму участнику; входящие обрабатываются в CallSignalHandler; на принимающей стороне показывается IncomingCallScreen, после принятия — экран звонка и аудио.

Если что-то работает «странно или неправильно», имеет смысл проверить: (1) что у звонящего и принимающего есть друг друга в `connected` и верно заполнен `peerNamesByEndpoint`; (2) логи в `NearbyMeshClient` (sendToPeer: no endpoint for peer X); (3) разрешения и жизненный цикл Nearby (один start в MainScreen, один клиент в Application).
