# Эволюция архитектуры: от dataflow к iFlexyModel

## История проекта

Проект прошёл путь от гибридной архитектуры к чистым микросервисам с единым pipeline.

## Старая архитектура (dataflow)

```
┌─────────────┐      ┌──────────────┐      ┌─────────────────────────────┐
│   facade    │──────▶│ orchestrator │──────▶│          validator            │
│  (Gateway)  │◄─────│ (pipeline)   │◄─────│  ┌─────────┬─────────┐        │
└─────────────┘      └──────────────┘      │  │  Auth   │  Docs   │        │
                                           │  │(Allow)  │(Mongo)  │        │
                                           │  ├─────────┼─────────┤        │
                                           │  │Validate │Enrich   │        │
                                           │  │(Schema) │(Groovy) │        │
                                           │  └─────────┴─────────┘        │
                                           └─────────────────────────────┘
```

**Проблемы:**
- `validator` = God Service (всё в одном)
- Смешение бизнес-логики и инфраструктуры
- Невозможно масштабировать отдельные функции
- Сложно добавлять новые стадии обработки

## Новая архитектура (iFlexyModel)

```
┌─────────┐   ┌───────────────┐   ┌─────────────┐   ┌─────────────┐   ┌──────────────┐
│ facade  │──▶│pipeline-router│──▶│enrichment   │──▶│  policy     │──▶│ validation   │
│(Gateway)│   │  (stateful)   │   │  (Groovy)   │   │  (RBAC)     │   │(JSON Schema) │
└─────────┘   └───────┬───────┘   └──────┬──────┘   └──────┬──────┘   └──────┬───────┘
                      │◄──────────────────┘◄─────────────────┘◄────────────────┘
                      │
                      ▼
              ┌─────────────┐
              │  document   │
              │  (CRUD+Hist)│
              └─────────────┘
```

**Преимущества:**
- Каждый сервис делает одно дело
- Горизонтальное масштабирование любой стадии
- Единый pipeline для всех моделей
- Stage-сервисы stateless, состояние в `pipeline-router`

## Маппинг кода

| dataflow файл | iFlexyModel сервис | Комментарий |
|---------------|-------------------|-------------|
| `facade/DocumentController.java` | `facade/` (TODO) | API Gateway |
| `facade/RedisAuthenticationProvider.java` | `facade/` | JWT + Redis |
| `facade/KafkaReplyHandler.java` | `pipeline-router/` | Request-Reply |
| `orchestrator/OrchestratorService.java` | `pipeline-router/PipelineOrchestrator.java` | Главная логика |
| `orchestrator/EtcdRegistration.java` | — | Заменено на Kafka + Redis |
| `validator/AllowService.java` | `policy-service/PolicyEvaluator.java` | RBAC |
| `validator/ExpressionService.java` | `validation-service/SchemaValidator.java` | JSON Schema вместо своего |
| `validator/BootstrapService.java` | `enrichment-service/ScriptExecutor.java` | Groovy sandbox |
| `validator/DocumentService.java` | `document-service/` (TODO) | CRUD + история |

## Что сохраняем

### Фронтенд (ui-admin)
```bash
cd C:\Users\light\Desktop\dataflow\ui-admin
# Работает без изменений с новым backend!
```

Админка использует тот же REST API (`/api/{model}/{action}`), поэтому совместима с новой архитектурой.

### Логика валидации
Из `validator/ExpressionService.java` → `validation-service/SchemaValidator.java`
- Было: собственная реализация проверок
- Стало: `networknt/json-schema-validator` (стандарт)

### Groovy обогащение
Из `validator/BootstrapService.java` → `enrichment-service/ScriptExecutor.java`
- Было: Groovy без sandbox
- Стало: `SecureASTCustomizer` + таймауты

## Что добавлено

| Функция | Реализация |
|---------|-----------|
| Кэширование | Caffeine (L1) + Redis Pub/Sub (инвалидация) |
| DLT | Dead Letter Topics для ошибок |
| Fail-fast | Настраиваемая прерываемость pipeline |
| Версионирование | Модели + схемы + правила версионируются |

## Миграция данных

1. **Схемы** из `validator/ImportSchemaDecorator.java` → `model-registry/`
2. **Документы** - остаются в MongoDB, совместимы
3. **Правила доступа** - конвертация в `PolicyRule` формат

## Репозитории

- **Legacy**: `C:\Users\light\Desktop\dataflow\` (работающий код)
- **New**: `C:\Users\light\Desktop\iFlexyModel\` (в разработке)
