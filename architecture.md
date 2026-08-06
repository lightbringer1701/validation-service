# FlexiModel — Архитектура системы

## Стек

Java 21, Spring Boot 3, Spring Cloud Stream (Kafka binder), Spring Data MongoDB, Spring Data Redis, Caffeine, Apache Kafka, MongoDB, Redis

## Общее описание

FlexiModel — платформа для управления динамическими моделями данных. Ключевая идея: аналитики и разработчики через UI описывают модели (схемы, правила доступа, скрипты предобработки, валидации, кастомные действия), а платформа обеспечивает единый pipeline обработки любых операций над этими моделями без написания кода под каждую сущность.

Система работает как движок: одна модель — набор правил, другая — другой набор, но путь обработки (обогащение → авторизация → валидация → сохранение) единый и управляемый через конфигурацию, а не через код.

**Основные цели:**
- Все операции (create / update / delete / custom action) проходят через единый управляемый pipeline
- Аналитики могут добавлять/изменять модели и правила без деплоя
- Синхронные и асинхронные операции через единую точку входа
- Горизонтальное масштабирование каждого сервиса независимо
- Полная история изменений документов
- Гибкая авторизация на уровне действий и полей

---

## Инфраструктура и окружение

### Apache Kafka
Основная шина данных между сервисами. Используется для:
- Передачи команд по pipeline (write path)
- Kafka Request-Reply для синхронных ответов на Gateway
- Уведомлений об изменениях конфигурации моделей

Каждый сервис подписывается как consumer group, что даёт балансировку нагрузки между инстансами. Число партиций в топиках должно быть не меньше максимального concurrency сервиса.

### MongoDB
Основное хранилище данных. Каждый сервис работает со своими коллекциями:
- `model-registry`: `models`, `model_versions`, `model_rules`
- `pipeline-router`: `pipeline_flows`, `pipeline_executions`
- `document-service`: `documents`, `document_history`

Используется optimistic locking через поле `version` в документах.

### Redis
Два сценария использования:
1. **JWT-сессии и blacklist** — хранение токенов на `facade` с TTL
2. **Redis Pub/Sub** — broadcast-инвалидация in-process кэшей во всех инстансах сервисов при изменении конфигурации моделей (в отличие от Kafka consumer group, Pub/Sub доставляет сообщение каждому подписчику)

### Кэширование (Caffeine)
In-process кэш в каждом сервисе для хранения актуальных схем, pipeline flow и правил. Двухуровневая схема: Caffeine (L1, in-process, TTL 5 мин) → Redis (L2, shared, TTL 30 мин) → MongoDB (источник истины).

---

## Топики Kafka

### Write path (команды)
```
cmd.incoming                  Gateway → pipeline-router
                              Единый топик для всех входящих команд.
                              Тип операции и модель — в заголовках сообщения.

pipeline.enrichment           pipeline-router → enrichment-service (и обратно)
pipeline.authorization        pipeline-router → policy-service (и обратно)
pipeline.validation           pipeline-router → validation-service (и обратно)
pipeline.document             pipeline-router → document-service (и обратно)
```

Ответы от stage-сервисов идут в тот же топик — `stageName` и `status` в теле сообщения говорят pipeline-router что делать дальше.

### Reply (синхронные ответы)
```
reply.gateway.{instanceId}   pipeline-router → facade
                              Каждый инстанс Gateway статично подписан на свою партицию.
                              correlationId в заголовке связывает запрос с ответом.
```

### Системные
```
model.schema.changed          model-registry → все сервисы
                              Публикуется в Kafka для аудита и для сервисов
                              с Kafka-консьюмером. Параллельно дублируется
                              через Redis Pub/Sub для инвалидации кэшей.

pipeline.config.changed       model-registry → pipeline-router
                              Только Redis Pub/Sub (broadcast всем инстансам).
```

### DLT (Dead Letter Topics)
```
cmd.incoming.DLT              Команды, упавшие после N retry
pipeline.stage.DLT            Ответы stage-сервисов с неустранимыми ошибками
```

---

## Сервисы

### facade

**Роль:** единственная точка входа в систему по HTTP/REST.

**Что делает:**
- Принимает REST-запросы от клиентов (браузер, мобильные приложения, внешние системы)
- Валидирует и декодирует JWT-токены; хранит blacklist токенов в Redis
- Определяет тип операции (`create` / `update` / `delete` / custom action) и целевую модель из URL/заголовков
- Формирует конверт команды: `correlationId`, `modelId`, `action`, `requestedBy`, `replyTopic`, флаг `sync/async`
- Для **синхронных** запросов — отправляет команду в `cmd.incoming`, подписывается на `reply.gateway.{instanceId}` и ждёт ответа (Kafka Request-Reply)
- Для **асинхронных** — отправляет команду и сразу возвращает `202 Accepted` с `correlationId`
- Маршрутизирует запросы на чтение напрямую в `query-service` (минуя pipeline)

**Горизонтальное масштабирование:**
Каждый инстанс статично назначен на свою партицию в reply-топике. При добавлении инстансов нужно заранее создать достаточно партиций. `instanceId` передаётся в заголовке команды, чтобы `pipeline-router` знал куда слать ответ.

**Взаимодействие:**
- → Kafka `cmd.incoming` (write path)
- ← Kafka `reply.gateway.{instanceId}` (sync reply)
- → `query-service` (read path, HTTP или Kafka — на усмотрение)
- ↔ Redis (JWT blacklist, сессии)

---

### pipeline-router

**Роль:** оркестратор pipeline. Знает порядок стадий обработки для каждой модели и действия, принимает решения о следующем шаге.

**Что делает:**
- Читает команду из `cmd.incoming`
- Загружает конфигурацию pipeline для пары `{modelId, action}` из кэша (Caffeine → MongoDB)
- Создаёт запись `PipelineExecution` в MongoDB (correlationId, текущая стадия, статус, payload)
- Последовательно отправляет сообщения на каждую стадию и ждёт ответа
- Если стадия вернула `FAILURE` и у неё `failFast: true` — прерывает pipeline, фиксирует ошибку
- Если все стадии пройдены — отправляет результат в `reply.gateway.{instanceId}`
- Обогащённый payload от `enrichment-service` передаётся дальше по цепочке (не оригинальный)

**Конфигурация pipeline** хранится в MongoDB (`pipeline_flows`) и кэшируется локально. При изменении конфигурации `model-registry` публикует событие в Redis Pub/Sub → все инстансы инвалидируют кэш и подгружают актуальную версию.

**Горизонтальное масштабирование:**
Состояние pipeline хранится в MongoDB, не в памяти — любой инстанс может продолжить обработку при перезапуске. `concurrency` настраивается в `application.yml` и определяет число параллельных потоков-консьюмеров.

**Взаимодействие:**
- ← Kafka `cmd.incoming`
- → Kafka `pipeline.enrichment`, `pipeline.authorization`, `pipeline.validation`, `pipeline.document`
- ← Kafka (те же топики, ответы от stage-сервисов)
- → Kafka `reply.gateway.{instanceId}`
- ↔ MongoDB `pipeline_flows`, `pipeline_executions`
- ← Redis Pub/Sub `pipeline.config.changed` (инвалидация кэша)

---

### enrichment-service

**Роль:** предобработка и обогащение входящего документа перед валидацией и сохранением.

**Что делает:**
- Получает payload и метаданные команды
- Загружает Groovy-скрипт для данной модели из кэша (Caffeine → `model-registry`)
- Выполняет скрипт в Groovy Sandbox (`SecureASTCustomizer`, ограничение времени выполнения)
- Возвращает обогащённый payload (`enrichedPayload`) обратно в `pipeline.enrichment`

**Типичные задачи скриптов:** автоподстановка `createdAt`/`updatedAt`, генерация slug-полей, дефолтные значения, нормализация форматов данных.

**Горизонтальное масштабирование:**
Сервис полностью stateless. Groovy-скрипты компилируются один раз и кэшируются в памяти (Caffeine). Инвалидация при изменении модели — через Kafka топик `model.schema.changed`.

**Взаимодействие:**
- ← Kafka `pipeline.enrichment`
- → Kafka `pipeline.enrichment` (ответ с `enrichedPayload`)
- → `model-registry` (загрузка скриптов, HTTP при cache miss)

---

### policy-service

**Роль:** проверка прав доступа и выполнение бизнес-правил авторизации.

**Что делает:**
- Получает команду с информацией о пользователе (`requestedBy`, роли из JWT)
- Загружает правила авторизации для пары `{modelId, action}` из кэша
- Выполняет правила — как статические (RBAC: роль → разрешённые действия), так и динамические (Groovy-скрипты с доступом к payload и контексту пользователя)
- Возвращает `SUCCESS` или `FAILURE` с кодом ошибки

**Примеры динамических правил:** пользователь может редактировать только свои документы, определённые поля доступны только администраторам, операция разрешена только в рабочее время.

**Горизонтальное масштабирование:**
Stateless, правила кэшируются локально. Инвалидация через `model.schema.changed`.

**Взаимодействие:**
- ← Kafka `pipeline.authorization`
- → Kafka `pipeline.authorization` (ответ)
- → `model-registry` (загрузка правил при cache miss)

---

### validation-service

**Роль:** валидация обогащённого документа по JSON Schema модели.

**Что делает:**
- Получает `enrichedPayload` (уже после обогащения)
- Загружает JSON Schema для `{modelId, modelVersion}` из кэша
- Выполняет валидацию (библиотека типа `networknt/json-schema-validator`)
- Возвращает `SUCCESS` или `FAILURE` со списком ошибок валидации

**Важно:** валидирует по версии схемы, указанной в команде (`modelVersion`) — не обязательно по `currentVersion`. Это позволяет обрабатывать документы, созданные под старую схему, корректно.

**Горизонтальное масштабирование:**
Stateless. Скомпилированные схемы кэшируются в Caffeine (ключ `modelId:version`).

**Взаимодействие:**
- ← Kafka `pipeline.validation`
- → Kafka `pipeline.validation` (ответ)
- → `model-registry` (загрузка схем при cache miss)

---

### document-service

**Роль:** CRUD-операции над документами в MongoDB.

**Что делает:**
- Выполняет `create` / `update` / `delete` документов в коллекции, соответствующей `modelId`
- Optimistic locking: при update проверяет поле `version` в документе; если не совпадает — возвращает конфликт
- Сохраняет запись в `document_history` при каждом изменении (кто, когда, что изменилось)
- Привязывает к документу `modelId` и `modelVersion` (под какой схемой создан)

**Горизонтальное масштабирование:**
Stateless. MongoDB обеспечивает атомарность операций через транзакции или атомарные операторы (`findOneAndUpdate` с условием на `version`).

**Взаимодействие:**
- ← Kafka `pipeline.document`
- → Kafka `pipeline.document` (ответ с результатом операции)
- ↔ MongoDB `documents`, `document_history`

---

### model-registry

**Роль:** центральный реестр всех моделей, их схем, правил и версий. Сердце системы.

**Что делает:**
- Предоставляет REST API для создания и управления моделями (UI для аналитиков)
- Хранит три сущности: `models` (заголовок), `model_versions` (иммутабельные версии схем), `model_rules` (Groovy-скрипты и правила)
- Версионирование по semver: MAJOR (breaking changes в схеме), MINOR (новые поля, обратно совместимо), PATCH (только правила/описания)
- При публикации новой версии: запись в MongoDB + событие в Kafka `model.schema.changed` + сигнал в Redis Pub/Sub `pipeline.config.changed`
- Поддерживает статусы модели: `DRAFT → REVIEW → PUBLISHED → DEPRECATED`
- Обслуживает запросы от других сервисов на получение схем при cache miss (REST)

**Горизонтальное масштабирование:**
Stateless. Все сервисы кэшируют схемы локально и обращаются в Registry только при cache miss или после инвалидации.

**Взаимодействие:**
- REST API ← UI аналитиков / разработчиков
- → Kafka `model.schema.changed`
- → Redis Pub/Sub `pipeline.config.changed`
- ↔ MongoDB `models`, `model_versions`, `model_rules`
- REST API ← `enrichment-service`, `policy-service`, `validation-service` (при cache miss)

---

### query-service

**Роль:** обработка запросов на чтение документов (CQRS read path).

**Что делает:**
- Принимает запросы на поиск и получение документов
- Читает из MongoDB (те же коллекции что и `document-service`, но через read-concern `majority` или из secondary)
- Поддерживает фильтрацию, пагинацию, сортировку
- Может применять field-level проекции на основе прав пользователя (какие поля видны данной роли)
- В перспективе — собственная read-модель или Elasticsearch для сложных запросов

**Горизонтальное масштабирование:**
Stateless, read-only. Нагрузку можно масштабировать независимо от write path.

**Взаимодействие:**
- ← `facade` (HTTP или Kafka)
- ↔ MongoDB `documents` (read-only)
- → `model-registry` (для загрузки схем проекций)

---

## Общий flow запроса (write, sync)

```
Client
  │ HTTP POST /api/{modelId}/{action}
  ▼
facade
  │ JWT check → Redis
  │ Формирует PipelineCommandEvent {correlationId, modelId, action, payload, sync=true}
  │ Kafka → cmd.incoming
  │ Ждёт ответа на reply.gateway.{instanceId}
  ▼
pipeline-router
  │ Загружает PipelineFlow из кэша
  │ Создаёт PipelineExecution в MongoDB
  │ Kafka → pipeline.enrichment
  ▼
enrichment-service
  │ Выполняет Groovy-скрипт
  │ Kafka → pipeline.enrichment {enrichedPayload}
  ▼
pipeline-router
  │ Обновляет currentPayload в PipelineExecution
  │ Kafka → pipeline.authorization
  ▼
policy-service
  │ Проверяет правила доступа
  │ Kafka → pipeline.authorization {SUCCESS}
  ▼
pipeline-router
  │ Kafka → pipeline.validation
  ▼
validation-service
  │ Валидирует по JSON Schema
  │ Kafka → pipeline.validation {SUCCESS}
  ▼
pipeline-router
  │ Kafka → pipeline.document
  ▼
document-service
  │ CRUD в MongoDB + запись в history
  │ Kafka → pipeline.document {SUCCESS, documentId}
  ▼
pipeline-router
  │ Обновляет PipelineExecution → COMPLETED
  │ Kafka → reply.gateway.{instanceId}
  ▼
facade
  │ HTTP 200 OK {documentId, ...}
  ▼
Client
```

---

## Обработка ошибок

**Retry:** каждый stage-сервис при временной ошибке (недоступность MongoDB, таймаут) бросает исключение. `DefaultErrorHandler` в Spring Kafka выполняет N retry с backoff, после чего отправляет сообщение в DLT-топик.

**failFast:** если у стадии выставлен `failFast: true` и она вернула `FAILURE` — `pipeline-router` прерывает pipeline и уведомляет `facade` об ошибке. Если `failFast: false` — pipeline продолжается.

**Timeout:** у каждой стадии свой `timeoutMs` в конфигурации pipeline. `pipeline-router` может отслеживать время ожидания ответа и завершать pipeline по таймауту (через scheduled job по `PipelineExecution` с устаревшим `updatedAt`).

**DLT-мониторинг:** сообщения в DLT-топиках должны мониториться и обрабатываться вручную или через отдельный recovery-сервис.

---

## Масштабирование

Все сервисы горизонтально масштабируемы. Ключевые параметры:

| Сервис | Состояние | Ключ масштабирования |
|---|---|---|
| facade | stateless | Число партиций в reply-топике |
| pipeline-router | stateful (MongoDB) | Число партиций cmd.incoming |
| enrichment-service | stateless | Число партиций pipeline.enrichment |
| policy-service | stateless | Число партиций pipeline.authorization |
| validation-service | stateless | Число партиций pipeline.validation |
| document-service | stateless | Число партиций pipeline.document |
| model-registry | stateless | Стандартный LB |
| query-service | stateless | Стандартный LB |

`concurrency` каждого сервиса задаётся в `application.yml` и определяет число параллельных Kafka-консьюмеров внутри одного инстанса.