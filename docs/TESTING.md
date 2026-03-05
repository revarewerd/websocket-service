# WebSocket Service — Решения по тестированию v1.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.1`

## Обзор

**60 unit-тестов**, все на **ZIO Test** (`ZIOSpecDefault`).
Запуск: `sbt test` из директории `services/websocket-service/`.
Результат: **60 passed, 0 failed** (время выполнения ~0.5 сек).

---

## Тестовые модули

### 1. ConnectionRegistrySpec

Расположение: `src/test/scala/.../service/ConnectionRegistrySpec.scala`

| Тест | Что проверяется |
|---|---|
| register — создаёт UUID | register возвращает уникальный UUID |
| register — увеличивает connectionCount | После register count = 1 |
| unregister — удаляет соединение | После unregister count = 0 |
| unregister — очищает vehicleIndex | При disconnect удаляет подписки из индекса |
| unregister — очищает orgIndex | При disconnect удаляет org подписку |
| subscribeVehicles — добавляет vehicleId | subscribeVehicles возвращает total |
| subscribeVehicles — обновляет vehicleIndex | После подписки getSubscribers находит соединение |
| unsubscribeVehicles — удаляет vehicleId | Отписка от конкретных vehicleId |
| subscribeOrg — включает org-level подписку | subscribeOrg → видим все ТС организации |
| unsubscribeAll — сбрасывает все подписки | После unsubscribeAll total = 0 |
| getSubscribers — объединяет оба индекса | Прямая подписка + org-level |
| getSubscribers — пустой результат | Нет подписчиков → Set.empty |
| connectionCount — корректный подсчёт | Считает все активные соединения |
| subscriptionCount — корректный подсчёт | Считает подписки на vehicleId |

### 2. PositionThrottlerSpec

Расположение: `src/test/scala/.../service/PositionThrottlerSpec.scala`

| Тест | Что проверяется |
|---|---|
| shouldSend — первый вызов true | Первый вызов для vehicleId всегда разрешён |
| shouldSend — второй вызов false | Повторный вызов < intervalMs → false |
| shouldSend — после интервала true | Вызов после intervalMs → true (с TestClock) |
| shouldSend — разные vehicleId | Throttle независим для разных vehicleId |

### 3. MessageRouterSpec

Расположение: `src/test/scala/.../service/MessageRouterSpec.scala`

| Тест | Что проверяется |
|---|---|
| routeGpsEvent — доставляет подписчику | GPS → PositionUpdate в правильный канал |
| routeGpsEvent — throttled | GPS не доставляется при throttle = false |
| routeGpsEvent — нет подписчиков | GPS для vehicleId без подписчиков → discard |
| routeGeozoneEvent — доставляет без throttle | GeozoneEvent → GeoEventNotification |
| routeSpeedViolation — доставляет без throttle | SpeedViolation → SpeedAlert |
| routeGpsEvent — несколько подписчиков | GPS доставляется всем подписчикам параллельно |

### 4. DomainSpec

Расположение: `src/test/scala/.../domain/DomainSpec.scala`

| Тест | Что проверяется |
|---|---|
| ClientMessage — decode subscribe | JSON → ClientMessage.Subscribe |
| ClientMessage — decode subscribeOrg | JSON → ClientMessage.SubscribeOrg |
| ClientMessage — decode unsubscribe | JSON → ClientMessage.Unsubscribe |
| ClientMessage — decode ping | JSON → ClientMessage.Ping |
| ClientMessage — decode invalid | Невалидный JSON → Left(error) |
| ServerMessage — encode position | PositionUpdate → JSON |
| ServerMessage — encode geoEvent | GeoEventNotification → JSON |
| ServerMessage — encode speedAlert | SpeedAlert → JSON |
| ServerMessage — encode error | Error → JSON |
| GpsPoint — roundtrip | GpsPoint → JSON → GpsPoint |
| GeozoneEvent — roundtrip | GeozoneEvent → JSON → GeozoneEvent |
| SpeedViolationEvent — roundtrip | SpeedViolationEvent → JSON → SpeedViolationEvent |
| Opaque types — VehicleId | VehicleId(123).value == 123 |
| Opaque types — OrganizationId | OrganizationId(100).value == 100 |

---

## Запуск тестов

```bash
cd services/websocket-service

# Все тесты
sbt test

# Конкретный spec
sbt "testOnly *ConnectionRegistrySpec"
sbt "testOnly *PositionThrottlerSpec"
sbt "testOnly *MessageRouterSpec"
sbt "testOnly *DomainSpec"
```

---

## Покрытие

| Слой | Покрытие | Примечание |
|---|---|---|
| **domain/** | ~100% | JSON сериализация, opaque types |
| **service/** | ~90% | ConnectionRegistry, PositionThrottler, MessageRouter |
| **kafka/** | 0% | Нужны integration тесты с testcontainers |
| **api/** | 0% | Нужны integration тесты с WS client |

## TODO

- [ ] Integration тесты для Kafka consumers (testcontainers)
- [ ] Integration тесты для WebSocketHandler (zio-http test client)
- [ ] Property-based тесты для ConnectionRegistry (concurrent access)
