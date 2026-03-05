# WebSocket Service

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

Сервис real-time трансляции GPS позиций и бизнес-событий через WebSocket. Принимает данные из Kafka (gps-events, geozone-events, rule-violations), фильтрует по подпискам клиентов и доставляет в WebSocket каналы.

## Характеристики

| Параметр | Значение |
|----------|----------|
| Порт | 8090 (HTTP + WebSocket) |
| Язык | Scala 3.4.0 + ZIO 2.0.20 |
| Протокол | WebSocket + JSON |
| Состояние | In-memory (ZIO Ref) |
| Паттерн | Smart Consumer |

## Быстрый старт

```bash
cd services/websocket-service
sbt compile
sbt run
```

### Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `HTTP_PORT` | `8090` | Порт HTTP + WebSocket |
| `THROTTLE_POSITION_INTERVAL_MS` | `1000` | Мин. интервал позиций (мс) |
| `KAFKA_GPS_EVENTS_TOPIC` | `gps-events` | Топик GPS позиций |
| `KAFKA_GEOZONE_EVENTS_TOPIC` | `geozone-events` | Топик событий геозон |
| `KAFKA_RULE_VIOLATIONS_TOPIC` | `rule-violations` | Топик нарушений скорости |

## Архитектура — Smart Consumer

```
Kafka (gps-events)     │
  ──────────────────┐  │  WebSocket клиенты
                     │  │
Kafka (geozone-events)│  │  Client A → подписка на vehicles [1,2,3]
  ──────────────────┐│  │  Client B → подписка на org (все ТС)
                     ││  │  Client C → подписка на vehicles [5,6]
     ┌───────────┐  ││  │
     │ Message   │◄─┘│  │  ┌──────────────────────┐
     │ Router    │◄──┘│  │  │ ConnectionRegistry   │
     │           │────┼──┼─►│   vehicleIndex       │
     │ +Throttle │    │  │  │   orgIndex            │
     └───────────┘    │  │  └──────────────────────┘
                      │  │
                      │  │  Router проверяет подписки,
                      │  │  фильтрует in-memory, отправляет
                      │  │  только нужным клиентам.
```

**Принцип:** сервис читает ВСЕ GPS точки из Kafka, но отправляет только тем клиентам, которые подписаны на соответствующие vehicleId или организацию. Дискардинг ненужных точек стоит ~50ns (HashMap lookup) — пренебрежимо мало.

## WebSocket протокол

### Подключение

```
ws://host:8090/ws?orgId=123
```

### Клиент → Сервер

```json
{"type": "subscribe", "vehicleIds": [1, 2, 3]}
{"type": "subscribeOrg"}
{"type": "unsubscribe", "vehicleIds": [1]}
{"type": "unsubscribeAll"}
{"type": "ping"}
```

### Сервер → Клиент

```json
{"type": "connected", "connectionId": "uuid"}
{"type": "subscribed", "vehicleIds": [1,2,3], "total": 5}
{"type": "position", "vehicleId": 1, "deviceId": 2, "latitude": 55.75, "longitude": 37.62, "speed": 45, ...}
{"type": "geoEvent", "eventType": "enter", "vehicleId": 1, "geozoneId": 5, "geozoneName": "Офис", ...}
{"type": "speedAlert", "vehicleId": 1, "currentSpeed": 95, "maxSpeed": 60, ...}
{"type": "pong"}
{"type": "error", "code": "INVALID_MESSAGE", "message": "..."}
```

## REST API

```
GET /health  — статус сервиса, кол-во соединений и подписок
```

## Kafka топики

| Топик | Consumer Group | Описание |
|---|---|---|
| `gps-events` | `ws-positions` | GPS позиции от CM |
| `geozone-events` | `ws-events` | Вход/выход геозон от Rule Checker |
| `rule-violations` | `ws-events` | Нарушения скорости от Rule Checker |

## Структура кода

```
src/main/scala/com/wayrecall/tracker/websocket/
├── Main.scala                    # Точка входа, ZIO layers
├── api/
│   ├── WebSocketHandler.scala    # WS endpoint, обработка команд
│   └── HealthRoutes.scala        # GET /health
├── config/
│   └── AppConfig.scala           # Конфигурация (HOCON)
├── domain/
│   ├── Entities.scala            # Opaque types, GpsPoint, events
│   ├── Messages.scala            # ClientMessage, ServerMessage (WS протокол)
│   └── Errors.scala              # Типизированные ошибки
├── kafka/
│   ├── GpsEventConsumer.scala    # Consumer gps-events
│   └── EventConsumer.scala       # Consumer geozone-events + rule-violations
└── service/
    ├── ConnectionRegistry.scala  # Реестр соединений + подписки (in-memory)
    ├── MessageRouter.scala       # Роутинг Kafka → WS каналы
    └── PositionThrottler.scala   # Rate limiting позиций
```

## TODO (пост-MVP)

- [ ] JWT аутентификация через API Gateway (вместо orgId в query)
- [ ] Redis Pub/Sub для горизонтального масштабирования
- [ ] Reconnect с восстановлением подписок (lastEventId)
- [ ] Batch-оптимизация (объединение позиций в один фрейм)
- [ ] Prometheus метрики (ws_active_connections, ws_messages_sent_total)
- [ ] Rate limiting входящих сообщений от клиента
- [ ] Unit тесты для ConnectionRegistry и MessageRouter
