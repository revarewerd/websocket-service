# WebSocket Service — API v1.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.0`

**Порт:** 8090
**Базовый URL:** `http://localhost:8090`

---

## WebSocket Endpoint

### WS /ws?orgId={id}

Основной endpoint для real-time подключения. Все данные организации фильтруются
по `orgId` из query параметра (в MVP). В post-MVP `orgId` извлекается из JWT токена.

**URL:** `ws://localhost:8090/ws?orgId=100`

**Query параметры:**

| Параметр | Тип | Обязателен | Описание |
|---|---|---|---|
| `orgId` | Long | Да | ID организации (multi-tenant фильтр) |

**Ответ при отсутствии `orgId`:**
```
HTTP 400 Bad Request
Body: "Отсутствует параметр orgId"
```

**Ответ при успешном подключении:**
```json
{"type":"connected","connectionId":"abc-123-def-456"}
```

---

## WS Протокол — Сообщения клиент → сервер

Все сообщения — JSON с дискриминатором `"type"`.

### Subscribe — подписка на конкретные ТС

```json
{
  "type": "subscribe",
  "vehicleIds": [1, 2, 3]
}
```

**Ответ:**
```json
{
  "type": "subscribed",
  "vehicleIds": [1, 2, 3],
  "total": 3
}
```

### SubscribeOrg — подписка на ВСЕ ТС организации

```json
{
  "type": "subscribeOrg"
}
```

**Ответ:**
```json
{
  "type": "subscribed",
  "vehicleIds": [],
  "total": 0
}
```

> Примечание: при subscribeOrg клиент получает PositionUpdate для ВСЕХ ТС организации
> без необходимости указывать vehicleIds. Используется для экрана "карта всех ТС".

### Unsubscribe — отписка от конкретных ТС

```json
{
  "type": "unsubscribe",
  "vehicleIds": [2]
}
```

**Ответ:**
```json
{
  "type": "unsubscribed",
  "vehicleIds": [2],
  "total": 2
}
```

### UnsubscribeAll — полная отписка

```json
{
  "type": "unsubscribeAll"
}
```

**Ответ:**
```json
{
  "type": "unsubscribed",
  "vehicleIds": [],
  "total": 0
}
```

### Ping — heartbeat

```json
{
  "type": "ping"
}
```

**Ответ:**
```json
{
  "type": "pong"
}
```

---

## WS Протокол — Сообщения сервер → клиент

### PositionUpdate — обновление GPS позиции

Отправляется при получении новой GPS точки из Kafka, если:
1. Клиент подписан на данный `vehicleId` или на всю организацию (subscribeOrg)
2. Прошло >= 1000мс с последней отправки для этого `vehicleId` (throttle)

```json
{
  "type": "position",
  "vehicleId": 12345,
  "deviceId": 67890,
  "latitude": 55.7558,
  "longitude": 37.6173,
  "speed": 65,
  "course": 180,
  "satellites": 12,
  "timestamp": "2026-03-03T10:30:00Z",
  "serverTimestamp": "2026-03-03T10:30:00.123Z"
}
```

### GeoEventNotification — событие геозоны

Доставляется **без throttle** (важное бизнес-событие).

```json
{
  "type": "geoEvent",
  "eventType": "leave",
  "vehicleId": 12345,
  "geozoneId": 456,
  "geozoneName": "Склад-01",
  "latitude": 55.7558,
  "longitude": 37.6173,
  "speed": 45,
  "timestamp": "2026-03-03T10:30:00Z"
}
```

### SpeedAlert — нарушение скорости

Доставляется **без throttle** (важное бизнес-событие).

```json
{
  "type": "speedAlert",
  "vehicleId": 12345,
  "currentSpeed": 95,
  "maxSpeed": 60,
  "latitude": 55.7558,
  "longitude": 37.6173,
  "timestamp": "2026-03-03T10:30:00Z"
}
```

### Error — ошибка

```json
{
  "type": "error",
  "code": "INVALID_MESSAGE",
  "message": "Невалидное сообщение: expected '\"' got 'x'"
}
```

---

## REST Endpoints

### GET /health

Health check — для K8s liveness probe и мониторинга.

**Request:**
```bash
curl http://localhost:8090/health
```

**Response (200 OK):**
```json
{
  "status": "healthy",
  "connections": 42,
  "subscriptions": 150
}
```

| Поле | Тип | Описание |
|---|---|---|
| `status` | String | Всегда `"healthy"` при работающем сервисе |
| `connections` | Int | Количество активных WS соединений |
| `subscriptions` | Int | Количество подписок на конкретные vehicleId |

---

## Коды ошибок WS

| Код | Описание | Когда |
|---|---|---|
| `INVALID_MESSAGE` | Невалидный JSON от клиента | Не распознанный формат сообщения |
| `MISSING_ORG_ID` | Отсутствует orgId | Подключение без query param |
| `AUTH_FAILED` | Ошибка аутентификации | Post-MVP: невалидный JWT |
| `SUBSCRIPTION_DENIED` | Нет прав на подписку | Post-MVP: vehicleId другой организации |
| `RATE_LIMIT` | Превышен лимит сообщений | Post-MVP: rate limiting |

---

## Примеры использования

### JavaScript (браузер)

```javascript
const ws = new WebSocket('ws://localhost:8090/ws?orgId=100');

ws.onopen = () => {
  // Подписка на конкретные ТС
  ws.send(JSON.stringify({
    type: 'subscribe',
    vehicleIds: [1, 2, 3]
  }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  switch (msg.type) {
    case 'position':
      updateMarker(msg.vehicleId, msg.latitude, msg.longitude, msg.speed);
      break;
    case 'geoEvent':
      showNotification(`ТС ${msg.vehicleId} ${msg.eventType} геозону ${msg.geozoneName}`);
      break;
    case 'speedAlert':
      showAlert(`ТС ${msg.vehicleId}: скорость ${msg.currentSpeed} > ${msg.maxSpeed} км/ч`);
      break;
  }
};

// Heartbeat каждые 30 секунд
setInterval(() => {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'ping' }));
  }
}, 30000);
```

### wscat (CLI)

```bash
# Подключение
wscat -c 'ws://localhost:8090/ws?orgId=100'

# Подписка
> {"type":"subscribe","vehicleIds":[1,2,3]}
< {"type":"subscribed","vehicleIds":[1,2,3],"total":3}

# Подписка на всю организацию
> {"type":"subscribeOrg"}

# Ping
> {"type":"ping"}
< {"type":"pong"}
```
