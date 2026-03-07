# PeerDone: архитектура и сравнение с эталоном (sowmen)

## Как устроен PeerDone

### Транспорт
- **Google Nearby Connections** (BLE + Wi‑Fi), стратегия `P2P_CLUSTER`: устройства сами находят друг друга и устанавливают соединения. Один экземпляр `NearbyMeshClient` в `PeerDoneApplication`, доступ через `LocalNearbyClient`.
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

## Текущее поведение (кратко)

- **Чаты**: сообщения и файлы идут через broadcast; в чате показываются только сообщения с `senderUserId == peerId`.
- **Звонки**: offer/answer/end и аудио идут через **sendToPeer(peerId, ...)** только второму участнику; входящие обрабатываются в CallSignalHandler; на принимающей стороне показывается IncomingCallScreen, после принятия — экран звонка и аудио.

Если что-то работает «странно или неправильно», имеет смысл проверить: (1) что у звонящего и принимающего есть друг друга в `connected` и верно заполнен `peerNamesByEndpoint`; (2) логи в `NearbyMeshClient` (sendToPeer: no endpoint for peer X); (3) разрешения и жизненный цикл Nearby (один start в MainScreen, один клиент в Application).
