# WebSocket Service — Runbook v1.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.0`

## Быстрый запуск

### Первый запуск (dev)

```bash
# 1. Поднять инфраструктуру (Kafka обязательна, Redis не нужен)
cd wayrecall-tracker
docker-compose up -d kafka

# 2. Проверить готовность Kafka
docker-compose exec kafka kafka-topics.sh --list --bootstrap-server localhost:9092

# 3. Убедиться что топики существуют
# gps-events, geozone-events, rule-violations
docker-compose exec kafka kafka-topics.sh \
  --describe --topic gps-events \
  --bootstrap-server localhost:9092

# 4. Запустить WebSocket Service
cd services/websocket-service
sbt run

# 5. Проверить здоровье
curl http://localhost:8090/health

# 6. Подключиться через wscat (npm install -g wscat)
wscat -c 'ws://localhost:8090/ws?orgId=100'
```

### Docker (production)

```bash
cd services/websocket-service
sbt assembly

docker build -t wayrecall/websocket-service:latest .

docker run -d \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e HTTP_PORT=8090 \
  -p 8090:8090 \
  wayrecall/websocket-service:latest
```

### Переменные окружения

| Переменная | Default | Описание |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker |
| `KAFKA_GPS_EVENTS_TOPIC` | `gps-events` | Топик GPS позиций |
| `KAFKA_GEOZONE_EVENTS_TOPIC` | `geozone-events` | Топик геозон |
| `KAFKA_RULE_VIOLATIONS_TOPIC` | `rule-violations` | Топик нарушений |
| `KAFKA_POSITIONS_GROUP_ID` | `ws-positions` | Consumer group GPS |
| `KAFKA_EVENTS_GROUP_ID` | `ws-events` | Consumer group событий |
| `HTTP_PORT` | `8090` | Порт HTTP + WS |
| `THROTTLE_POSITION_INTERVAL_MS` | `1000` | Мин. интервал PositionUpdate (мс) |

---

## Диагностика

### Проверить состояние сервиса

```bash
curl http://localhost:8090/health | jq
```

**Ожидаемый ответ:**
```json
{
  "status": "healthy",
  "connections": 0,
  "subscriptions": 0
}
```

### Проверить WebSocket соединение

```bash
# Подключение
wscat -c 'ws://localhost:8090/ws?orgId=100'

# В открытом WS- сессии:
> {"type":"ping"}
< {"type":"pong"}

> {"type":"subscribe","vehicleIds":[1,2,3]}
< {"type":"subscribed","vehicleIds":[1,2,3],"total":3}
```

### Проверить Kafka consumer lag

```bash
# Consumer group для GPS позиций
docker-compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group ws-positions

# Consumer group для событий
docker-compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group ws-events
```

### Проверить что GPS данные доходят

```bash
# Читать gps-events напрямую (для дебага)
docker-compose exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic gps-events \
  --from-beginning --max-messages 5
```

---

## Типичные проблемы

### 1. WebSocket не подключается (HTTP 400)

**Симптомы:** Клиент получает `400 Bad Request`.

**Причина:** Отсутствует параметр `orgId` в URL.

**Решение:**
```bash
# Неправильно:
wscat -c 'ws://localhost:8090/ws'

# Правильно:
wscat -c 'ws://localhost:8090/ws?orgId=100'
```

### 2. Позиции не приходят после подписки

**Симптомы:** Клиент подписан, но не получает PositionUpdate.

**Причины:**
1. Connection Manager не запущен (нет GPS данных в Kafka)
2. Kafka consumer lag — WS Service не успевает читать
3. PositionThrottler фильтрует (интервал <1сек)
4. VehicleId не совпадает с тем, что шлёт CM

**Диагностика:**
```bash
# Проверить что данные в Kafka есть
docker-compose exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic gps-events --max-messages 1

# Проверить consumer lag
docker-compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group ws-positions

# Проверить логи WS Service
# Должен быть: "Запуск GPS Consumer: topic=gps-events, group=ws-positions"
```

### 3. Бизнес-события не доходят (гео/скорость)

**Симптомы:** Подписан, GPS позиции приходят, но GeoEventNotification и SpeedAlert — нет.

**Причины:**
1. Rule Checker не запущен
2. Нет активных правил для vehicleId
3. Consumer group `ws-events` отстаёт

**Диагностика:**
```bash
# Проверить данные в топике
docker-compose exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic geozone-events --max-messages 1

docker-compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group ws-events
```

### 4. Высокий Kafka consumer lag

**Симптомы:** `ws-positions` lag растёт, позиции приходят с задержкой.

**Причины:**
- Слишком много GPS точек (>10K/sec)
- Медленная отправка через WebSocket (блокирующие каналы)

**Решение:**
```bash
# Увеличить max.poll.records
export KAFKA_MAX_POLL_RECORDS=1000

# Или уменьшить throttle (отправлять реже)
export THROTTLE_POSITION_INTERVAL_MS=2000
```

### 5. WS соединения обрываются

**Симптомы:** Клиенты часто переподключаются.

**Причины:**
- Сетевые проблемы
- GC паузы JVM
- Отсутствие ping/pong (таймаут на стороне прокси/nginx)

**Решение:**
- Клиент должен отправлять `{"type":"ping"}` каждые 30 сек
- Настроить nginx: `proxy_read_timeout 120s;`

---

## Graceful Shutdown

WebSocket Service реализует graceful shutdown через ZIO:
1. Получает SIGTERM/SIGINT
2. Останавливает Kafka consumers (завершает текущий batch)
3. Закрывает HTTP/WS сервер (перестаёт принимать новые подключения)
4. Отключает все активные WS соединения
5. Завершает процесс

**В Docker:**
```bash
docker stop --time 30 websocket-service
```

---

## Мониторинг

### Health check (K8s)

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8090
  initialDelaySeconds: 15
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /health
    port: 8090
  initialDelaySeconds: 10
  periodSeconds: 5
```

### Ключевые метрики (для Prometheus — post-MVP)

| Метрика | Описание |
|---|---|
| `ws_connections_active` | Кол-во активных WS соединений |
| `ws_subscriptions_total` | Кол-во подписок на vehicleId |
| `ws_messages_sent_total` | Всего отправлено WS сообщений |
| `ws_kafka_lag` | Consumer lag для ws-positions |
| `ws_throttled_total` | Кол-во пропущенных позиций (throttle) |
