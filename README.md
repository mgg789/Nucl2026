# PeerDone

**Децентрализованный P2P мессенджер для обмена сообщениями, файлами и звонками без интернета.**

PeerDone позволяет общаться напрямую через локальную сеть или P2P-соединения. Идеально подходит для ситуаций, когда централизованная инфраструктура недоступна: массовые мероприятия, закрытые площадки, аварийные сценарии.

## Возможности

- **Связь без интернета** — приложение работает полностью в локальной сети
- **Auto-обнаружение** — автоматический поиск устройств в сети
- **P2P Архитектура** — данные идут напрямую между устройствами
- **P2P Звонки** — голосовая связь без серверов
- **Сквозное шифрование** — безопасность на уровне устройств
- **Мультихоп** — пересылка через промежуточные узлы
- **Передача файлов** — с контролем целостности и возобновлением

## Скриншоты

Приложение выполнено в соответствии с дизайном Figma:
- Онбординг с 5 информационными слайдами
- Настройка профиля с уникальным ID
- Поиск и добавление друзей
- Список чатов с фильтрами
- Экран чата с пузырьковыми сообщениями
- История звонков
- Карта сети с топологией
- Настройки и безопасность

## Архитектура

```
┌─────────────────────────────────────────────────────────────────────┐
│                           PeerDone App                              │
├─────────────────────────────────────────────────────────────────────┤
│  UI Layer (Jetpack Compose)                                         │
│  ├── OnboardingScreen, ProfileSetupScreen, PeerDiscoveryScreen      │
│  ├── ChatListScreen, ChatScreen                                     │
│  ├── CallsScreen, NetworkScreen, SettingsScreen                     │
│  └── Components (PeerAvatar, MessageBubble, BottomNavBar)           │
├─────────────────────────────────────────────────────────────────────┤
│  Navigation (Jetpack Navigation Compose)                            │
│  └── Screen sealed class, PeerDoneNavGraph                          │
├─────────────────────────────────────────────────────────────────────┤
│  Business Logic                                                     │
│  ├── SendOrchestrator — очередь отправки, retry, выбор транспорта   │
│  ├── AccessPolicy — политики шифрования и доступа                   │
│  └── FileTransferPlanner — chunking + SHA-256                       │
├─────────────────────────────────────────────────────────────────────┤
│  Data Layer                                                         │
│  ├── NearbyMeshClient — P2P discovery, соединения, messaging        │
│  ├── DeviceIdentityStore — идентификация устройства                 │
│  ├── PreferencesStore — DataStore для настроек                      │
│  └── IdentityTrustStore — доверенные ключи                          │
├─────────────────────────────────────────────────────────────────────┤
│  Transport Layer                                                    │
│  ├── TransportRegistry — выбор оптимального транспорта              │
│  ├── NearbyTransportAdapter — Google Nearby Connections             │
│  └── TransportStrategy — INTERACTIVE / RELIABLE / REALTIME          │
└─────────────────────────────────────────────────────────────────────┘
```

## Технологии

- **Android** + Kotlin + Jetpack Compose
- **Google Play Services Nearby** (P2P транспорт)
- **Kotlin Coroutines/Flow** для асинхронности
- **Android Keystore** для подписи сообщений
- **DataStore Preferences** для хранения настроек
- **Navigation Compose** для навигации

## Требования

- Android Studio (актуальная версия)
- Android SDK 36
- Минимум 2 Android-устройства для демонстрации
- `adb` в PATH

## Быстрый запуск

### Сборка

```bash
cd app
./gradlew :app:assembleDebug
```

### Установка на устройства

```bash
cd app
./scripts/build_and_install_connected.sh
```

## Демонстрационный сценарий

1. Запустить приложение на 2-3 устройствах
2. Пройти онбординг и настроить профиль
3. На экране "Поиск людей" найти друг друга
4. Перейти в чаты и обменяться сообщениями
5. Проверить вкладку "Сеть" для просмотра топологии
6. Показать мультихоп через третье устройство

## Критерии хакатона

| Критерий | Реализация |
|----------|------------|
| Обнаружение узлов | ✅ Nearby Connections |
| Установка P2P-сессии | ✅ Автоматическая |
| Обмен сообщениями | ✅ С ACK и retry |
| Мультихоп | ✅ TTL + forwarding |
| Передача файлов | ✅ Chunks + SHA-256 |
| Real-time звонки | ✅ Signaling layer |
| Шифрование | ✅ Android Keystore |
| Аутентификация | ✅ Device ID + подпись |
| Красивый UI | ✅ По Figma-дизайну |

## Структура проекта

```
app/src/main/java/com/peerdone/app/
├── MainActivity.kt
├── navigation/
│   ├── Screen.kt
│   └── PeerDoneNavGraph.kt
├── ui/
│   ├── onboarding/
│   ├── screens/
│   ├── components/
│   └── theme/
├── data/
├── domain/
├── core/
│   ├── transport/
│   ├── message/
│   ├── call/
│   └── file/
└── service/
```

## Документация

- `app/PROJECT_TECH_GUIDE_RU.md` — технический гайд
- `app/TECH_FLOW_ON_FINGERS_RU.md` — поток данных
- `app/P2P_CRYPTO_PLAYBOOK_RU.md` — криптография
- `app/ARCHITECTURE_DIAGRAM_RU.md` — архитектура

## Лицензия

MIT License

---

**PeerDone** — связь без границ и серверов.
