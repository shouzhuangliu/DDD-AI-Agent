# Request-Level Model Switching Design

## Goal

Allow a user to choose an enabled language model from the chat page and apply that choice to each individual question in both Auto and ReAct execution modes.

The existing DeepSeek configuration remains available. SenseNova 6.7 Flash-Lite is added as a second OpenAI-compatible provider and model.

## User Experience

The chat header contains a model selector populated from the backend model catalog. The current selection is included with every chat request as `modelId`.

The selector may be changed before any message. Changing it affects the next message only and does not rewrite the Agent default model or previous messages. The conversation history remains associated with the existing session.

If no model is selected, the backend preserves current behavior and uses the Agent/client default model.

## Model Catalog

Add `GET /api/v1/models` to return enabled and safely displayable model metadata:

- `modelId`
- `modelName`
- `modelType`
- `providerName`
- `configured`

The endpoint must never return an API key, credential placeholder, or full credential-bearing database object. Models with a missing credential remain visible but disabled so the reason is clear to the user.

Initial catalog entries are:

- `2001`: the existing DeepSeek model and API configuration.
- `2002`: `sensenova-6.7-flash-lite` using API configuration `1002`.

SenseNova API `1002` uses base URL `https://token.sensenova.cn` and completions path `/v1/chat/completions`.

## Credential Handling

No provider key is written into committed Java, YAML, SQL, HTML, documentation, or browser responses.

Database seed data stores credential references rather than secrets:

- DeepSeek API `1001`: `${DEEPSEEK_API_KEY}`
- SenseNova API `1002`: `${SENSENOVA_API_KEY}`

At startup, the backend resolves these references from the Spring `Environment`. Missing references mark the associated model unavailable instead of failing the complete application startup.

For the current developer machine, IDEA receives the environment variables through a local ignored run configuration or another user-local mechanism. Existing keys must not be printed during verification.

## Request Contract

Add nullable `modelId` to both `AutoAgentRequestDTO` and `ExecuteCommandEntity`.

`POST /api/v1/agent/auto_agent` validates a nonblank requested model before starting asynchronous execution:

- The model must exist and be enabled.
- Its API credential must be configured.
- Its request-scoped model bean and Auto client variants must be registered.

Validation failure returns an SSE error event with a safe message and does not call any provider. Blank `modelId` keeps the current Agent/client default behavior.

## Runtime Model Registry

Introduce a model registry responsible for loading every enabled model and its API configuration, resolving credentials, and registering or caching an `OpenAiChatModel` by `modelId`.

The registry exposes only safe metadata to controllers. Provider credentials remain encapsulated inside the registered model/API objects.

Registration is idempotent. Duplicate startup paths must reuse or replace the same logical model without producing conflicting Spring bean definitions.

## Auto Mode Routing

Auto mode uses several role-specific `ChatClient` instances, each with its own system prompt, advisors, and tools. Changing only the `model` option is insufficient because DeepSeek and SenseNova have different base URLs and credentials.

During armory initialization, build request-selectable client variants as a cross-product of:

- each configured Auto client/role; and
- each available model in the model registry.

Variant bean names include both identifiers, for example `ai_client_3101_model_2002`. The existing `ai_client_3101` bean remains the default for backward compatibility.

All Auto nodes resolve a client with `(clientId, request.modelId)`. A blank request model selects the existing default bean. A nonblank request model selects the cross-model variant while preserving the role's system prompt, MCP tools, and advisors.

## ReAct Mode Routing

ReAct already uses an `OpenAiChatModel` bean directly. It selects:

1. `request.modelId` when supplied and valid;
2. otherwise the Agent's configured `modelId`;
3. otherwise the existing default `2001`.

LLM logs record the selected model ID/name rather than always recording the Agent default.

## Frontend Changes

The existing single-file page loads `/api/v1/models` when the chat view opens and renders a selector beside the Agent mode badge.

Configured models are selectable. Unconfigured models are shown disabled with a `未配置密钥` label. The selected `modelId` is added to the existing JSON request body for every message.

The key is never stored in JavaScript, DOM attributes, local storage, or network responses.

## Database Initialization

Extend `init_data.sql` idempotently with API `1002` and model `2002`. Preserve API/model `1001`/`2001` and all existing client bindings.

Model selection is request-scoped, so the implementation must not update `ai_client_config`, `ai_agent.model_id`, or other shared database bindings during a chat request.

## Error Handling

- Unknown model: reject before asynchronous execution with `模型不存在或已禁用`.
- Missing credential: reject with `模型密钥未配置`.
- Provider authentication failure: send a safe provider-call failure message without including request headers or keys.
- Provider timeout or transient failure: retain the execution strategy's existing bounded retry behavior.

Logs may include `modelId`, provider name, and status, but never the credential or full authorization header.

## Testing and Acceptance

Automated tests cover:

- nullable/default and explicit `modelId` request mapping;
- safe model catalog responses with no API key field;
- default model resolution;
- request override resolution;
- invalid and unconfigured model rejection;
- Auto client variant selection;
- ReAct request override precedence;
- LLM logging of the actual selected model.

Integration acceptance requires:

- complete Maven package success;
- both model entries visible in the frontend selector;
- one minimal DeepSeek chat request and one minimal SenseNova chat request;
- distinct model IDs recorded for the two calls;
- no key present in Git diff, application logs, browser responses, or the model catalog payload.

Live provider checks must use a minimal prompt and must not print credentials.

## Non-Goals

- Persisting one model for an entire session.
- Changing an Agent's stored default model from the chat selector.
- Exposing provider credential management in the browser.
- Automatically migrating old conversation history between models.
