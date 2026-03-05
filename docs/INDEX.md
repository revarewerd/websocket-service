# WebSocket Service — Документация v1.0

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-03` | Версия: `1.0`

## Содержание

| # | Документ | Описание |
|---|---|---|
| 1 | [README.md](README.md) | Обзор, быстрый старт, зависимости |
| 2 | [ARCHITECTURE.md](ARCHITECTURE.md) | Внутренняя архитектура, Mermaid-диаграммы, Smart Consumer pattern |
| 3 | [API.md](API.md) | WebSocket протокол (5 клиент. + 8 серверн. сообщений), REST /health |
| 4 | [DATA_MODEL.md](DATA_MODEL.md) | In-memory state (3 Ref), Kafka модели, opaque types |
| 5 | [KAFKA.md](KAFKA.md) | 3 consume топика (gps-events, geozone-events, rule-violations), 0 produce |
| 6 | [DECISIONS.md](DECISIONS.md) | 7 ADR: Smart Consumer, Dual Index, In-Memory, Throttle, Latest, Port, OrgId |
| 7 | [RUNBOOK.md](RUNBOOK.md) | Запуск, диагностика, типичные проблемы, переменные окружения |
| 8 | [TESTING.md](TESTING.md) | Тестирование: 4 модуля (Registry, Throttler, Router, Domain) |
| 9 | [LEARNING.md](LEARNING.md) | Гайд для разработчика: как разобраться в коде с нуля |

## Быстрые ссылки

- **Запустить WS Service:** [RUNBOOK.md#быстрый-запуск](RUNBOOK.md#быстрый-запуск)
- **Подключиться через wscat:** [RUNBOOK.md#проверить-websocket-соединение](RUNBOOK.md#проверить-websocket-соединение)
- **WS протокол с примерами:** [API.md](API.md)
- **Kafka топики:** [KAFKA.md](KAFKA.md)
- **Почему Smart Consumer:** [DECISIONS.md#adr-001](DECISIONS.md#adr-001-smart-consumer-вместо-выделенного-kafka-топика)

## Внешние ссылки

- **Общая архитектура:** [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
- **Block 3 (Presentation):** [docs/ARCHITECTURE_BLOCK3.md](../../../docs/ARCHITECTURE_BLOCK3.md)
- **Kafka топики (все):** [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md)
- **Спецификация (подробная):** [docs/services/WEBSOCKET_SERVICE.md](../../../docs/services/WEBSOCKET_SERVICE.md)

## Зависимости от других сервисов

| Сервис | Тип связи | Описание |
|---|---|---|
| Connection Manager | Kafka (gps-events) | Источник GPS позиций |
| Rule Checker | Kafka (geozone-events, rule-violations) | Источник бизнес-событий |
| API Gateway (будущее) | HTTP reverse proxy | Аутентификация и маршрутизация |
