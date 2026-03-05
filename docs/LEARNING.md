> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.0`

# 📖 Изучение WebSocket Service

> Руководство для разработчика: как разобраться в коде, архитектуре и жизненном цикле данных.

---

## 1. Назначение сервиса

**WebSocket Service (WS)** — конечная точка real-time доставки данных до браузера.

Что делает:
- Принимает WebSocket подключения от веб-клиентов
- Читает GPS-точки и бизнес-события из Kafka
- Фильтрует in-memory по подпискам каждого клиента
- Доставляет только нужные данные через WebSocket

**Порт:** 8090 (HTTP + WebSocket на одном порту)

Что **не** делает:
- Не хранит данные в БД (нет PostgreSQL/TimescaleDB)
- Не публикует в Kafka (consume-only)
- Не парсит GPS пакеты (это CM)
- Не проверяет правила (это Rule Checker)

---

## 2. Архитектура и слои

### Обзор слоёв

```
[Web Client] → WS:8090 → WebSocketHandler → ConnectionRegistry
                                                    ↑ ↑
                              MessageRouter ────────┘ │
                                ↑      ↑              │
                     PositionThrottler  │              │
                                ↑      │              │
                   GpsEventConsumer    EventConsumer   │
                         ↑                  ↑         │
                    Kafka: gps-events  geozone-events  │
                                       rule-violations │
                                                       │
                                                   HealthRoutes → GET /health
```

### Слои подробно

| Слой | Файлы | Назначение |
|---|---|---|
| **domain/** | Entities.scala, Messages.scala, Errors.scala | Модели, WS протокол, ошибки |
| **config/** | AppConfig.scala | HOCON конфигурация + ZIO Layer |
| **service/** | ConnectionRegistry.scala, PositionThrottler.scala, MessageRouter.scala | Бизнес-логика, state |
| **kafka/** | GpsEventConsumer.scala, EventConsumer.scala | Kafka consumers |
| **api/** | WebSocketHandler.scala, HealthRoutes.scala | HTTP/WS endpoints |
| — | Main.scala | Точка входа, ZIO Layer граф |

---

## 3. Разбор файлов

### `Main.scala` — Точка входа

Собирает ZIO Layer граф:
1. Загружает `AppConfig` из `application.conf`
2. Разбивает на `KafkaConfig`, `HttpConfig`, `ThrottleConfig`
3. Создаёт сервисы: `ConnectionRegistry`, `PositionThrottler`, `MessageRouter`
4. Создаёт Kafka consumers: `GpsEventConsumer`, `EventConsumer`
5. Запускает 3 fiber'а через `.race()`:
   - HTTP/WS сервер (`Server.serve`)
   - GPS consumer (`gpsConsumer.run`)
   - Events consumer (`eventsConsumer.run`)

### `config/AppConfig.scala` — Конфигурация

```scala
final case class AppConfig(
  kafka: KafkaConfig,     // bootstrapServers, topics, groups
  http: HttpConfig,       // port
  throttle: ThrottleConfig // positionIntervalMs
)
```

- `deriveConfig[AppConfig].mapKey(toKebabCase)` — автоматический вывод из HOCON
- `AppConfig.live` — ZIO Layer из application.conf
- Переменные окружения переопределяют HOCON defaults

### `domain/Entities.scala` — Входящие Kafka модели

6 opaque types (совместимы с CM и Rule Checker):
- `VehicleId`, `DeviceId`, `OrganizationId`, `GeozoneId`, `Imei`, `SpeedRuleId`

3 Kafka модели:
- `GpsPoint` — из `gps-events` (14 полей, derives JsonCodec)
- `GeozoneEvent` — из `geozone-events` (enter/leave, 9 полей)
- `SpeedViolationEvent` — из `rule-violations` (10 полей)

### `domain/Messages.scala` — WS протокол

**ClientMessage** (клиент → сервер, `@jsonDiscriminator("type")`):
- `Subscribe(vehicleIds: List[Long])` → подписка на конкретные ТС
- `SubscribeOrg()` → подписка на всю организацию
- `Unsubscribe(vehicleIds: List[Long])` → отписка
- `UnsubscribeAll()` → полная отписка
- `Ping()` → heartbeat

**ServerMessage** (сервер → клиент):
- `Connected(connectionId)` — при подключении
- `Subscribed(vehicleIds, total)` — подтверждение подписки
- `Unsubscribed(vehicleIds, total)` — подтверждение отписки
- `PositionUpdate(vehicleId, lat, lon, speed, ...)` — GPS позиция
- `GeoEventNotification(eventType, vehicleId, ...)` — вход/выход из геозоны
- `SpeedAlert(vehicleId, currentSpeed, maxSpeed, ...)` — превышение скорости
- `Pong()` — ответ на ping
- `Error(code, message)` — ошибка

### `domain/Errors.scala` — Типизированные ошибки

`WsError` sealed trait с подтипами:
- `AuthenticationFailed` — невалидный JWT (post-MVP)
- `SubscriptionDenied` — нет прав на vehicleId
- `RateLimitExceeded` — слишком много сообщений
- `InvalidMessage` — невалидный JSON
- `ConnectionClosed` — WS закрыт
- `KafkaError` — ошибка Kafka
- `MissingOrganizationId` — нет orgId

### `service/ConnectionRegistry.scala` — Ядро подписочной модели

**Главный компонент.** Хранит все WS соединения и управляет подписками.

In-memory state (3 ZIO Ref):
```
connections:  Map[UUID, ActiveConnection]    — все соединения
vehicleIndex: Map[Long, Set[UUID]]           — vehicleId → подписчики
orgIndex:     Map[Long, Set[UUID]]           — orgId → org-level подписчики
```

Ключевой метод — `getSubscribersForVehicle(vehicleId, orgId)`:
```scala
vehicleIndex[vehicleId] ∪ orgIndex[orgId] → Set[WebSocketChannel]
```

Объединяет прямые подписки и org-level подписки. O(1) lookup.

При disconnect (`unregister`):
- Удаляет из `connections`
- Очищает `vehicleIndex` для всех подписанных vehicleId
- Очищает `orgIndex` если была org-level подписка

### `service/PositionThrottler.scala` — Ограничитель частоты

```
Ref[Map[Long, Instant]]  — vehicleId → время последней отправки
```

`shouldSend(vehicleId)`:
- Если прошло >= `positionIntervalMs` с последней отправки → true
- Иначе → false (пропускаем, экономим трафик)

### `service/MessageRouter.scala` — Маршрутизатор

Получает события, находит подписчиков, отправляет сообщения:

| Метод | Kafka → | ServerMessage | Throttle |
|---|---|---|---|
| `routeGpsEvent` | GpsPoint | PositionUpdate | Да (1/sec) |
| `routeGeozoneEvent` | GeozoneEvent | GeoEventNotification | Нет |
| `routeSpeedViolation` | SpeedViolationEvent | SpeedAlert | Нет |

`sendToChannels` — отправка параллельно через `ZIO.foreachParDiscard`.
Ошибки отдельных каналов логируются, но не прерывают доставку остальным.

### `kafka/GpsEventConsumer.scala` — Consumer GPS

- Читает топик `gps-events` (от CM)
- Consumer group: `ws-positions`
- auto.offset.reset: `latest`
- Десериализует JSON → GpsPoint → `router.routeGpsEvent(point)`

### `kafka/EventConsumer.scala` — Consumer бизнес-событий

- Читает 2 топика: `geozone-events` + `rule-violations` (от Rule Checker)
- Consumer group: `ws-events`
- Роутинг по `record.record.topic()`:
  - `geozone-events` → `router.routeGeozoneEvent`
  - `rule-violations` → `router.routeSpeedViolation`

### `api/WebSocketHandler.scala` — WS endpoint

Route: `GET /ws?orgId=N`

Жизненный цикл соединения:
1. Извлечь orgId из `queryParams`
2. `register(orgId, channel)` → uuid
3. Отправить `Connected(uuid)` клиенту
4. `receiveAll` — слушать команды (subscribe/unsubscribe/ping)
5. `.ensuring(unregister(uuid))` — очистка при любом завершении

### `api/HealthRoutes.scala` — Health endpoint

Route: `GET /health`
Возвращает JSON: `{"status":"healthy","connections":N,"subscriptions":N}`

---

## 4. Потоки данных

### GPS точка от трекера до браузера

```
GPS трекер → TCP:5001 → CM (парсинг + фильтрация)
  → Kafka: gps-events (GpsEventMessage)
    → WS: GpsEventConsumer (deserialize → GpsPoint)
      → MessageRouter.routeGpsEvent
        → PositionThrottler: прошло ≥1000мс?
          → Нет: discard
          → Да:
            → ConnectionRegistry.getSubscribersForVehicle(vehicleId, orgId)
              → vehicleIndex ∪ orgIndex → Set[channel]
                → channel.send(PositionUpdate.toJson)
                  → [WebSocket] → браузер → Leaflet карта обновляет маркер
```

### Событие геозоны

```
GPS трекер → CM → Kafka: gps-events-rules
  → Rule Checker (проверяет ST_Contains)
    → Kafka: geozone-events (GeozoneEvent)
      → WS: EventConsumer
        → MessageRouter.routeGeozoneEvent (NO throttle!)
          → ConnectionRegistry.getSubscribers → channels
            → channel.send(GeoEventNotification.toJson)
              → [WebSocket] → браузер показывает уведомление
```

---

## 5. Как добавить новый тип события

Пример: добавить `MaintenanceAlert` (напоминание о ТО).

1. **domain/Entities.scala** — добавить модель:
   ```scala
   final case class MaintenanceEvent(
     vehicleId: VehicleId,
     organizationId: OrganizationId,
     ...
   ) derives JsonCodec
   ```

2. **domain/Messages.scala** — добавить ServerMessage:
   ```scala
   @jsonHint("maintenanceAlert")
   final case class MaintenanceAlert(
     vehicleId: Long,
     ...
   ) extends ServerMessage
   ```

3. **service/MessageRouter.scala** — добавить метод:
   ```scala
   def routeMaintenanceEvent(event: MaintenanceEvent): UIO[Unit]
   ```

4. **kafka/EventConsumer.scala** — добавить обработку нового топика:
   ```scala
   else if topic == kafkaConfig.maintenanceEventsTopic then
     ...
   ```

5. **config/AppConfig.scala** — добавить название топика в KafkaConfig

6. **application.conf** — добавить default значение

7. **Обновить документацию:** API.md, KAFKA.md, DATA_MODEL.md

---

## 6. Как разобраться в проекте с нуля

**Порядок чтения файлов для нового разработчика:**

1. **README.md** (этот docs/) — общее понимание
2. **ARCHITECTURE.md** — Mermaid-диаграммы, потоки данных
3. **DECISIONS.md** — почему сделано именно так (ADR)
4. **domain/Entities.scala** — что приходит из Kafka
5. **domain/Messages.scala** — WS протокол (клиент↔сервер)
6. **service/ConnectionRegistry.scala** — ядро (как хранятся подписки)
7. **service/MessageRouter.scala** — как сообщения доходят до клиентов
8. **kafka/GpsEventConsumer.scala** — как читаются данные из Kafka
9. **api/WebSocketHandler.scala** — как обрабатывается WS подключение
10. **Main.scala** — как всё собирается вместе
11. **KAFKA.md** — какие топики потребляет
12. **API.md** — WS протокол с примерами
