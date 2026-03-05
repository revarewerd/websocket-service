# WebSocket Service — Модель данных v1.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.0`

## Обзор

WebSocket Service **не имеет собственной базы данных**.
Всё состояние хранится in-memory в ZIO Ref.
Kafka используется как источник данных (consume only).

---

## In-Memory State (ZIO Ref)

### ConnectionRegistry — 3 Ref'а

#### 1. connections: `Ref[Map[UUID, ActiveConnection]]`

Хранит все активные WebSocket соединения.

| Поле | Тип | Описание |
|---|---|---|
| `id` | UUID | Уникальный ID соединения |
| `organizationId` | OrganizationId (Long) | ID организации клиента |
| `channel` | WebSocketChannel | Netty канал для отправки сообщений |
| `subscribedVehicles` | Set[VehicleId] | Набор vehicleId, на которые подписан |
| `subscribedToOrg` | Boolean | Подписан ли на ВСЕ ТС организации |
| `connectedAt` | Instant | Время подключения |

#### 2. vehicleIndex: `Ref[Map[Long, Set[UUID]]]`

Обратный индекс: по vehicleId быстро находим все соединения, подписанные на это ТС.

```
vehicleId → {connId1, connId2, connId3}
```

**Операции:**
- `addToIndex(vehicleId, connId)` — при Subscribe
- `removeFromIndex(vehicleId, connId)` — при Unsubscribe или Disconnect
- Lookup O(1) по vehicleId

#### 3. orgIndex: `Ref[Map[Long, Set[UUID]]]`

Обратный индекс: по orgId быстро находим все соединения с подпиской на всю организацию.

```
organizationId → {connId4, connId5}
```

**Операции:**
- `addToIndex(orgId, connId)` — при SubscribeOrg
- `removeFromIndex(orgId, connId)` — при UnsubscribeAll или Disconnect
- Lookup O(1) по orgId

### Пример состояния

```
connections:
  uuid-1 → ActiveConnection(orgId=100, vehicles={1,2,3}, subscribedToOrg=false)
  uuid-2 → ActiveConnection(orgId=100, vehicles={}, subscribedToOrg=true)
  uuid-3 → ActiveConnection(orgId=200, vehicles={5}, subscribedToOrg=false)

vehicleIndex:
  1 → {uuid-1}
  2 → {uuid-1}
  3 → {uuid-1}
  5 → {uuid-3}

orgIndex:
  100 → {uuid-2}
```

**Поиск подписчиков для vehicleId=1, orgId=100:**
1. `vehicleIndex[1]` → `{uuid-1}` (прямая подписка)
2. `orgIndex[100]` → `{uuid-2}` (org-level подписка)
3. Результат: `{uuid-1, uuid-2}` — оба получат PositionUpdate

---

## PositionThrottler — 1 Ref

### lastSent: `Ref[Map[Long, Instant]]`

Хранит время последней отправки PositionUpdate для каждого vehicleId.

```
vehicleId → lastSentAt
1         → 2026-03-03T10:30:00.500Z
2         → 2026-03-03T10:30:01.200Z
```

**Логика:**
```
shouldSend(vehicleId):
  now = Clock.instant
  last = lastSent[vehicleId]
  if (now - last) < positionIntervalMs:
    return false  // слишком рано, пропускаем
  else:
    lastSent[vehicleId] = now
    return true   // разрешаем отправку
```

**Параметр:** `positionIntervalMs` = 1000мс (конфигурируемый)

---

## Kafka сообщения — Входящие модели

> Полное описание всех топиков → [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md)

### GpsPoint (из топика `gps-events`)

```json
{
  "vehicleId": 12345,
  "deviceId": 67890,
  "organizationId": 100,
  "imei": "352094080055555",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "altitude": 150.0,
  "speed": 65,
  "course": 180,
  "satellites": 12,
  "timestamp": "2026-03-03T10:30:00Z",
  "serverTimestamp": "2026-03-03T10:30:00.123Z",
  "hasGeozones": true,
  "hasSpeedRules": false
}
```

### GeozoneEvent (из топика `geozone-events`)

```json
{
  "eventType": "Leave",
  "vehicleId": 12345,
  "organizationId": 100,
  "geozoneId": 456,
  "geozoneName": "Склад-01",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "speed": 45,
  "timestamp": "2026-03-03T10:30:00Z"
}
```

### SpeedViolationEvent (из топика `rule-violations`)

```json
{
  "violationType": "SpeedLimitExceeded",
  "vehicleId": 12345,
  "organizationId": 100,
  "ruleId": 789,
  "currentSpeed": 95,
  "maxSpeed": 60,
  "latitude": 55.7558,
  "longitude": 37.6173,
  "timestamp": "2026-03-03T10:30:00Z"
}
```

---

## Доменные модели (Scala)

### Opaque Types

| Тип | Базовый | Используется |
|---|---|---|
| `VehicleId` | Long | Подписки, индексы, роутинг |
| `DeviceId` | Long | PositionUpdate |
| `OrganizationId` | Long | Multi-tenant фильтрация |
| `GeozoneId` | Long | GeoEventNotification |
| `Imei` | String | Десериализация GpsPoint |
| `SpeedRuleId` | Long | SpeedViolationEvent |

### Доменные ошибки

| Ошибка | Когда |
|---|---|
| `AuthenticationFailed` | Невалидный JWT (post-MVP) |
| `SubscriptionDenied` | vehicleId не принадлежит организации |
| `RateLimitExceeded` | Слишком много сообщений от клиента |
| `InvalidMessage` | Не парсится JSON от клиента |
| `ConnectionClosed` | WebSocket канал закрылся |
| `KafkaError` | Ошибка Kafka consumer |
| `MissingOrganizationId` | Нет orgId в query params |

---

## Redis (post-MVP)

В текущей реализации Redis не используется. 

**Планируется для горизонтального масштабирования:**

| Ключ | Тип | TTL | Назначение |
|---|---|---|---|
| `ws:subscriptions:{instanceId}` | SET | — | Синхронизация подписок между инстансами |
| `ws:connections:count` | STRING | — | Общее кол-во соединений (для балансировки) |

**Redis Pub/Sub каналы (post-MVP):**

| Канал | Назначение |
|---|---|
| `ws:sync:subscribe` | Синхронизация новых подписок |
| `ws:sync:unsubscribe` | Синхронизация отписок |
