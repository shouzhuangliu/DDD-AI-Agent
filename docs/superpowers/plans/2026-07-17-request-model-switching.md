# Request-Level Model Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add DeepSeek and SenseNova to a safe backend model catalog and let the web chat select either model independently for every message in Auto and ReAct modes.

**Architecture:** All enabled API/model definitions are loaded into Spring at startup. Auto mode registers a `ChatClient` variant for every role/model pair, while ReAct selects the requested `OpenAiChatModel` directly; a nullable `modelId` flows from the browser through the request DTO and execution entity. Provider keys are environment references resolved only on the server.

**Tech Stack:** Java 17+, Spring Boot 3.4.3, Spring AI 1.0.0, MyBatis, MySQL 8, JUnit 4, vanilla HTML/JavaScript.

## Global Constraints

- Preserve DeepSeek API/model IDs `1001`/`2001` and add SenseNova IDs `1002`/`2002`.
- SenseNova uses `https://token.sensenova.cn`, `/v1/chat/completions`, and `sensenova-6.7-flash-lite`.
- Never expose or commit DeepSeek/SenseNova keys.
- Blank request `modelId` preserves the current Agent/client default.
- A chat request must never update shared Agent or client-model database bindings.
- The user requested in-place development with no Git commits.

---

### Task 1: Externalize provider credentials and seed SenseNova

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/ModelCredentialResolver.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/model/ModelCredentialResolverTest.java`
- Modify: `init_data.sql`

**Interfaces:**
- Produces: `String resolve(String configuredValue)` and `boolean isConfigured(String configuredValue)`.
- Consumes: Spring environment variables `DEEPSEEK_API_KEY` and `SENSENOVA_API_KEY`.

- [ ] **Step 1: Write failing credential resolution tests**

```java
package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.service.armory.ModelCredentialResolver;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.Assert.*;

public class ModelCredentialResolverTest {
    @Test
    public void resolvesEnvironmentReferenceWithoutExposingReference() {
        MockEnvironment env = new MockEnvironment().withProperty("SENSENOVA_API_KEY", "secret-value");
        ModelCredentialResolver resolver = new ModelCredentialResolver(env);
        assertEquals("secret-value", resolver.resolve("${SENSENOVA_API_KEY}"));
        assertTrue(resolver.isConfigured("${SENSENOVA_API_KEY}"));
    }

    @Test
    public void missingEnvironmentReferenceIsUnavailable() {
        ModelCredentialResolver resolver = new ModelCredentialResolver(new MockEnvironment());
        assertNull(resolver.resolve("${SENSENOVA_API_KEY}"));
        assertFalse(resolver.isConfigured("${SENSENOVA_API_KEY}"));
    }

    @Test
    public void acceptsExistingDatabaseCredentialDuringMigration() {
        ModelCredentialResolver resolver = new ModelCredentialResolver(new MockEnvironment());
        assertEquals("legacy-local-key", resolver.resolve("legacy-local-key"));
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=ModelCredentialResolverTest test
```

Expected: compilation fails because `ModelCredentialResolver` does not exist.

- [ ] **Step 3: Implement the credential resolver**

```java
package cn.bugstack.ai.domain.agent.service.armory;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelCredentialResolver {
    private static final Pattern ENV_REFERENCE = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)}$");
    private final Environment environment;

    public ModelCredentialResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) return null;
        Matcher matcher = ENV_REFERENCE.matcher(configuredValue.trim());
        String value = matcher.matches() ? environment.getProperty(matcher.group(1)) : configuredValue.trim();
        return value == null || value.isBlank() ? null : value;
    }

    public boolean isConfigured(String configuredValue) {
        return resolve(configuredValue) != null;
    }
}
```

- [ ] **Step 4: Replace seed credentials and add SenseNova idempotently**

Use these API/model statements at the start of `init_data.sql`:

```sql
INSERT INTO `ai_client_api` (`api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`)
VALUES ('1001', 'https://api.deepseek.com', '${DEEPSEEK_API_KEY}', '/v1/chat/completions', '/v1/embeddings', 1)
ON DUPLICATE KEY UPDATE `base_url`=VALUES(`base_url`), `api_key`=VALUES(`api_key`), `completions_path`=VALUES(`completions_path`), `status`=VALUES(`status`);

INSERT INTO `ai_client_api` (`api_id`, `base_url`, `api_key`, `completions_path`, `embeddings_path`, `status`)
VALUES ('1002', 'https://token.sensenova.cn', '${SENSENOVA_API_KEY}', '/v1/chat/completions', '', 1)
ON DUPLICATE KEY UPDATE `base_url`=VALUES(`base_url`), `api_key`=VALUES(`api_key`), `completions_path`=VALUES(`completions_path`), `status`=VALUES(`status`);

INSERT INTO `ai_client_model` (`model_id`, `api_id`, `model_name`, `model_type`, `status`)
VALUES ('2001', '1001', 'deepseek-v4-flash', 'chat', 1)
ON DUPLICATE KEY UPDATE `api_id`=VALUES(`api_id`), `model_name`=VALUES(`model_name`), `status`=VALUES(`status`);

INSERT INTO `ai_client_model` (`model_id`, `api_id`, `model_name`, `model_type`, `status`)
VALUES ('2002', '1002', 'sensenova-6.7-flash-lite', 'chat', 1)
ON DUPLICATE KEY UPDATE `api_id`=VALUES(`api_id`), `model_name`=VALUES(`model_name`), `status`=VALUES(`status`);
```

- [ ] **Step 5: Run the resolver tests and scan for committed keys**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=ModelCredentialResolverTest test
rg -n "sk-[A-Za-z0-9]" init_data.sql ai-agent-station-study-*/src/main ai-agent-station-study-*/src/test
```

Expected: three tests pass; the scan finds no newly stored provider key. Pre-existing test fixtures must be reviewed separately and never copied.

---

### Task 2: Load all enabled models and build Auto client variants

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/AiModelBeanName.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/model/AiModelBeanNameTest.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/adapter/repository/IAgentRepository.java`
- Modify: `ai-agent-station-study-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/adapter/repository/AgentRepository.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/business/data/impl/AiClientLoadDataStrategy.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/AiClientApiNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/AiClientModelNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/AiClientNode.java`

**Interfaces:**
- Produces: model bean `ai_client_model_<modelId>` and Auto variant `ai_client_<clientId>_model_<modelId>`.
- Produces repository method `queryEnabledModelVOList()` and reuses the existing `queryAiClientApiVOListByModelIds(List<String>)` method.

- [ ] **Step 1: Write failing bean-name tests**

```java
package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.model.valobj.AiModelBeanName;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AiModelBeanNameTest {
    @Test public void buildsRequestScopedClientBeanName() {
        assertEquals("ai_client_3101_model_2002", AiModelBeanName.clientVariant("3101", "2002"));
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=AiModelBeanNameTest test
```

Expected: compilation fails because `AiModelBeanName` does not exist.

- [ ] **Step 3: Implement the bean-name utility**

```java
package cn.bugstack.ai.domain.agent.model.valobj;

public final class AiModelBeanName {
    private AiModelBeanName() {}
    public static String clientVariant(String clientId, String modelId) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(clientId) + "_model_" + modelId;
    }
}
```

- [ ] **Step 4: Add enabled-model repository queries**

Add to `IAgentRepository`:

```java
List<AiClientModelVO> queryEnabledModelVOList();
```

Implement it in `AgentRepository` by mapping `aiClientModelDao.queryEnabledModels()`. Reuse the repository's existing `queryAiClientApiVOListByModelIds(List<String>)` method for API lookup; do not add a duplicate interface method.

- [ ] **Step 5: Merge all enabled models/APIs into armory load data**

In `AiClientLoadDataStrategy`, after the existing client-based futures resolve, merge:

```java
List<AiClientModelVO> enabledModels = repository.queryEnabledModelVOList();
List<String> enabledModelIds = enabledModels.stream().map(AiClientModelVO::getModelId).toList();
List<AiClientApiVO> enabledApis = repository.queryAiClientApiVOListByModelIds(enabledModelIds);
dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(), enabledModels);
dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(), enabledApis);
```

- [ ] **Step 6: Resolve credentials and skip unavailable providers safely**

Inject `ModelCredentialResolver` into `AiClientApiNode`, resolve `apiKey`, and skip registration with a warning containing only `apiId` when it is unavailable. In `AiClientModelNode`, check `applicationContext.containsBean(apiBeanName)` before calling `getBean`; skip the model when its API was not registered.

- [ ] **Step 7: Register role/model ChatClient variants**

In `AiClientNode`, preserve creation of the existing default client. Then loop over loaded `AiClientModelVO` values whose model beans exist and build another `ChatClient` with the same system prompt, tools, and advisors but the loop's `OpenAiChatModel`. Register it using `AiModelBeanName.clientVariant(clientId, modelId)`.

- [ ] **Step 8: Run focused tests and compile**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=AiModelBeanNameTest,ModelCredentialResolverTest test
mvn -DskipTests package
```

Expected: focused tests and the seven-module build succeed.

---

### Task 3: Carry and validate request-level model selection

**Files:**
- Create: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/model/ModelSelectionService.java`
- Create: `ai-agent-station-study-app/src/test/java/cn/bugstack/ai/test/agent/model/ModelSelectionServiceTest.java`
- Modify: `ai-agent-station-study-api/src/main/java/cn/bugstack/ai/api/dto/AutoAgentRequestDTO.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/model/entity/ExecuteCommandEntity.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AiAgentController.java`

**Interfaces:**
- Produces: nullable `String modelId` from HTTP request to domain execution.
- Produces: `ModelSelectionService.requireAvailable(String modelId)`.

- [ ] **Step 1: Write failing availability tests**

```java
package cn.bugstack.ai.test.agent.model;

import cn.bugstack.ai.domain.agent.service.model.ModelSelectionService;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.junit.Assert.*;

public class ModelSelectionServiceTest {
    @Test public void blankModelKeepsDefault() {
        new ModelSelectionService(new StaticApplicationContext()).requireAvailable(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownModelIsRejected() {
        new ModelSelectionService(new StaticApplicationContext()).requireAvailable("2999");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=ModelSelectionServiceTest test
```

Expected: compilation fails because the service does not exist.

- [ ] **Step 3: Implement model validation**

```java
package cn.bugstack.ai.domain.agent.service.model;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentEnumVO;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ModelSelectionService {
    private final ApplicationContext context;
    public ModelSelectionService(ApplicationContext context) { this.context = context; }
    public void requireAvailable(String modelId) {
        if (modelId == null || modelId.isBlank()) return;
        String bean = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId.trim());
        if (!context.containsBean(bean)) throw new IllegalArgumentException("模型不存在、已禁用或密钥未配置");
    }
}
```

- [ ] **Step 4: Add `modelId` to request and execution objects**

Add this field to both Lombok data classes:

```java
private String modelId;
```

- [ ] **Step 5: Validate before async execution and forward the value**

Inject `ModelSelectionService` into `AiAgentController`, call `requireAvailable(request.getModelId())` before `threadPoolExecutor.execute`, and include:

```java
.modelId(request.getModelId())
```

When validation throws, send one structured SSE error event and complete the emitter without logging request credentials.

- [ ] **Step 6: Run request validation tests**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=ModelSelectionServiceTest test
```

Expected: both tests pass.

---

### Task 4: Route Auto and ReAct through the requested model

**Files:**
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/AbstractExecuteSupport.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/Step0IntentClassifierNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/Step1AnalyzerNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/Step2PrecisionExecutorNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/Step3QualitySupervisorNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto/step/Step4LogExecutionSummaryNode.java`
- Modify: `ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/react/ReActExecuteStrategy.java`

**Interfaces:**
- Consumes: `ExecuteCommandEntity.modelId` and beans registered in Task 2.
- Produces: all model calls for one request use the selected provider/model.

- [ ] **Step 1: Add request-aware Auto client lookup**

```java
protected ChatClient getChatClientByClientId(String clientId, String modelId) {
    String beanName = (modelId == null || modelId.isBlank())
            ? AiAgentEnumVO.AI_CLIENT.getBeanName(clientId)
            : AiModelBeanName.clientVariant(clientId, modelId.trim());
    return getBean(beanName);
}
```

- [ ] **Step 2: Update all six Auto lookup sites**

Replace each call with:

```java
getChatClientByClientId(clientId, requestParameter.getModelId())
```

This includes both lookups in `Step0IntentClassifierNode` and one lookup in Steps 1–4.

- [ ] **Step 3: Apply ReAct precedence and accurate logging**

Use:

```java
String selectedModelId = requestParameter.getModelId();
if (selectedModelId == null || selectedModelId.isBlank()) selectedModelId = agent.getModelId();
if (selectedModelId == null || selectedModelId.isBlank()) selectedModelId = "2001";
String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(selectedModelId);
```

Use `selectedModelId` in `LlmLogEntry.modelName(...)` and log fields.

- [ ] **Step 4: Compile and inspect every lookup**

```powershell
rg -n "getChatClientByClientId" ai-agent-station-study-domain/src/main/java/cn/bugstack/ai/domain/agent/service/execute/auto
mvn -DskipTests package
```

Expected: every Auto step supplies `requestParameter.getModelId()` and the full build succeeds.

---

### Task 5: Expose a safe model catalog

**Files:**
- Create: `ai-agent-station-study-api/src/main/java/cn/bugstack/ai/api/dto/AiModelOptionDTO.java`
- Modify: `ai-agent-station-study-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentController.java`

**Interfaces:**
- Produces: `GET /api/v1/models` returning safe model options only.

- [ ] **Step 1: Create the response DTO without a credential field**

```java
package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AiModelOptionDTO {
    private String modelId;
    private String modelName;
    private String modelType;
    private String providerName;
    private boolean configured;
}
```

- [ ] **Step 2: Add the safe catalog endpoint**

Inject `IAiClientModelDao`, `IAiClientApiDao`, and `ModelCredentialResolver`. Map `queryEnabledModels()` to `AiModelOptionDTO`, fetch each model's API internally, derive provider label (`DeepSeek`, `SenseNova`, or the API host), and compute `configured` through the resolver. Do not serialize `AiClientApi`.

- [ ] **Step 3: Verify payload safety while the app is running**

```powershell
$models = Invoke-RestMethod http://127.0.0.1:8091/api/v1/models
$models | ConvertTo-Json -Depth 4
if (($models | ConvertTo-Json) -match 'apiKey|sk-') { throw 'Credential leaked from model catalog' }
```

Expected: entries `2001` and `2002` appear; no key field or key-shaped value appears.

---

### Task 6: Add the per-message frontend selector

**Files:**
- Modify: `ai-agent-station-study-trigger/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `GET /api/v1/models`.
- Produces: `modelId` in every `/api/v1/agent/auto_agent` request.

- [ ] **Step 1: Add the selector beside the mode badge**

```html
<label for="chatModelSelect" style="font-size:12px;color:#64748b;margin-left:12px">模型</label>
<select id="chatModelSelect" style="border:1px solid #e2e8f0;border-radius:6px;padding:5px 8px;font-size:12px"></select>
```

- [ ] **Step 2: Load safe model options**

```javascript
async function loadChatModels() {
  const select = document.getElementById('chatModelSelect');
  const models = await fetch(API + '/models').then(r => r.json());
  select.innerHTML = models.map(m =>
    '<option value="'+esc(m.modelId)+'" '+(!m.configured?'disabled':'')+'>'+
    esc(m.providerName+' · '+m.modelName+(m.configured?'':'（未配置密钥）'))+'</option>'
  ).join('');
}
```

Call `loadChatModels()` from `showChatView()` and retain the current selector value when the list refreshes.

- [ ] **Step 3: Send the selected model on every message**

Add to the existing request body:

```javascript
modelId: document.getElementById('chatModelSelect').value
```

- [ ] **Step 4: Build and inspect the packaged page**

```powershell
mvn -DskipTests package
jar tf ai-agent-station-study-app/target/ai-agent-station-study-app.jar | Select-String 'static/index.html'
```

Expected: build succeeds and the page exists in the executable JAR.

---

### Task 7: Configure local secrets and perform live two-model acceptance

**Files:**
- Local ignored configuration only; no committed key-bearing file.

**Interfaces:**
- Consumes: user-provided DeepSeek and SenseNova keys.
- Produces: verified requests through model IDs `2001` and `2002`.

- [ ] **Step 1: Configure secrets only in the local process/IDEA environment**

Set `DEEPSEEK_API_KEY` and `SENSENOVA_API_KEY` without printing their values. Update the current local database API rows to the literal environment references, not the key values.

- [ ] **Step 2: Restart the application and verify the safe catalog**

```powershell
java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar --spring.profiles.active=dev
```

Expected: port `8091` starts; `/api/v1/models` shows `2001` and `2002` configured.

- [ ] **Step 3: Send one minimal DeepSeek request**

POST to `/api/v1/agent/auto_agent` with a fresh session, `mode=react`, `modelId=2001`, and message `只回复：DeepSeek OK`.

Expected: SSE produces a final/complete response and no authentication error.

- [ ] **Step 4: Send one minimal SenseNova request**

POST with another fresh session, `mode=react`, `modelId=2002`, and message `只回复：SenseNova OK`.

Expected: SSE produces a final/complete response and no authentication error.

- [ ] **Step 5: Verify distinct model logs and no secret leakage**

```powershell
docker compose -f compose.local.yml exec -T mysql mysql -uroot -p123456 -D ai-agent-station-study -e "SELECT session_id, model_name, status FROM ai_llm_log ORDER BY id DESC LIMIT 2;"
rg -n "sk-[A-Za-z0-9]" data ai-agent-station-study-app/data -g "*.log"
```

Expected: logs identify `2001` and `2002`; no provider key appears in application logs.

- [ ] **Step 6: Run final verification**

```powershell
mvn -pl ai-agent-station-study-app -am -DskipTests=false -Dtest=ModelCredentialResolverTest,AiModelBeanNameTest,ModelSelectionServiceTest test
mvn -DskipTests package
./scripts/local-env.ps1 verify
```

Expected: focused tests pass, the full build succeeds, and both databases remain healthy.
