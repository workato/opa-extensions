# Timer extension — demo of `AgentWebhookApiV1`

## Context

`AgentWebhookApiV1` только что добавили в Extension API (34.0): он даёт `send(url, body, headers, policy)` с
ретраями/backoff и `wakeup(...)` поверх HTTP, плюс `WebhookResponse.as(Class)` для JSON-десериализации. В репозитории
нет расширения, которое бы его демонстрировало — есть только `SecurityExtension` (SHA-256) и `NtlmExtension` (
NTLM-прокси), оба синхронные request/response.

Цель — добавить третье демонстрационное расширение «таймер»: Workato-рецепт подписывается с `{message, period_seconds}`,
а расширение периодически шлёт обратно webhook с этим сообщением. Это самый короткий способ показать `AgentWebhookApiV1`
в деле (ретраи, `X-Workato-Event-Id`, dedup) и одновременно продемонстрировать webhook-trigger SDK-паттерн (
`webhook_subscribe/notification/unsubscribe`), которого в репо тоже ещё нет.

Single-node и in-memory состояние — осознанное ограничение демо (подтверждено пользователем).
Референс [peaz/workato-webhook-opa](https://github.com/peaz/workato-webhook-opa) — reverse-proxy, не периодический
эмиттер; оттуда берём только форму webhook-триггера в Ruby и привычку закрывать ресурсы в lifecycle-хуке. Отправку
вручную через `HttpURLConnection` целиком заменяет `AgentWebhookApiV1.send`.

## Пользовательские решения (зафиксированы)

- Период — одно поле `period_seconds` (integer, min 5 в проде; `min-period-seconds=1` в тест-фикстуре).
- Ключ подписки — расширение генерит UUID, SDK получает его как `id` из `/subscribe` и возвращает обратно как
  `recipe_id` в `/unsubscribe` (строго по README §subscribe/unsubscribe).
- Payload: `{message, generated_at, tick, subscription_id, period_seconds}`.
- Коннектор экспортирует webhook-триггер + action `fire_now` (ручной тик — удобно для VCR-спеки, не надо тикать
  планировщиком).

## Java — `TimerExtension`

**Файл:** `src/main/java/com/mycompany/onprem/timer/TimerExtension.java`
**Пакет:** `com.mycompany.onprem.timer`
**Контроллер:** `@RestController @RequestMapping("/webhook")`

Эндпоинты:

| Метод                       | Путь                 | Контракт                                                                                                                                                                     |
|-----------------------------|----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET  /webhook/health`      | —                    | Пустое 200 OK. Нужен для `connector.test`-лямбды (peaz-конвенция).                                                                                                           |
| `POST /webhook/subscribe`   | `SubscribeRequest`   | body: `{workato_webhook_url, recipe_id, connection_id, message, period_seconds}`. Отвечает `{"id": "<uuid>"}`. 400, если `period_seconds < min-period-seconds` или не задан. |
| `POST /webhook/unsubscribe` | `UnsubscribeRequest` | body: `{recipe_id (= наш UUID), connection_id}`. 204.                                                                                                                        |
| `POST /webhook/fire_now`    | `FireNowRequest`     | body: `{subscription_id}`. Синхронно шлёт одно сообщение. 200 `{delivered, status_code}` или 404.                                                                            |

Внутренности:

- `GET /webhook/health` — `return ResponseEntity.ok().build();`. Один метод, нужен только для `connector.test`-лямбды.
- **Все JSON-границы — через типизированные `public record` с `@JsonProperty("snake_case")`** (чтобы
  Jackson/ObjectMapper знал snake_case-ключи; имена компонентов record'а компилируются в camelCase, auto-translation'а
  нет — README API это специально оговаривает). Никаких `Map<String, Object>` в сигнатурах контроллеров и в
  webhook-payload.
    - **Входные:** `SubscribeRequest(workato_webhook_url, recipe_id, connection_id, message, period_seconds)`,
      `UnsubscribeRequest(recipe_id, connection_id)`, `FireNowRequest(subscription_id)`.
    - **Ответы:** `SubscribeResponse(id)`, `FireNowResponse(delivered, status_code)`, `ErrorResponse(error)`.
      Контроллеры возвращают `ResponseEntity<SubscribeResponse>` и т. д. — Spring MVC сериализует через свой Jackson (
      общий `ObjectMapper` агента).
    - **Исходящий webhook-payload:** `TimerEvent(message, generated_at, tick, subscription_id, period_seconds)` record.
      Передаётся в `agent.getWebhookApi().send(url, event, headers)` — API сериализует через свой shared ObjectMapper (
      README API §Response: *"a shared Jackson ObjectMapper"*).
- `Map<String, Subscription> active = new ConcurrentHashMap<>()`. `Subscription` хранит `webhookUrl`, `message`,
  `periodSeconds`, `tick (AtomicLong)`, `future (ScheduledFuture<?>)`.
- Один shared `ScheduledExecutorService` на расширение, инициализируется лениво через double-check locking — зеркало
  паттерна `NtlmExtension#client()` (`src/main/java/com/mycompany/onprem/ntlm/NtlmExtension.java:181`). Daemon-поток с
  именем `timer-ext-<n>`.
- Задача использует `scheduleWithFixedDelay`, а не `scheduleAtFixedRate`: `AgentWebhookApiV1.send` синхронный и может
  блокироваться на всю цепочку ретраев, fixed-rate накопил бы очередь на одном потоке. Пауза `period_seconds` считается
  от конца предыдущего тика.
- В обработчике тика: `tick.incrementAndGet()`, построить `TimerEvent`, вызвать
  `agent.getWebhookApi().send(url, event, Map.of("X-Workato-Event-Id", UUID.randomUUID().toString()))`. Любые
  исключения (`WebhookDeliveryException` после исчерпания ретраев и прочее) — ловить и логировать WARN, не ронять
  поток. (Map здесь — для заголовков, не для JSON-payload.)
- `fire_now` вызывает тот же `send` синхронно, но исключение *пробрасывает* (клиент видит ошибку в HTTP-ответе).
  Возвращает `FireNowResponse(resp.isSuccessful(), resp.statusCode())`.
- `@Value("${min-period-seconds:5}")` — чтобы тесты могли опустить порог до 1.
- `@PreDestroy void shutdown()` — отменяет все `ScheduledFuture`, очищает `active`, `scheduler.shutdownNow()` +
  `awaitTermination(2, SECONDS)`. Плановый агент подтвердил, что `AgentExtensionHarness` закрывает Spring-контекст (
  `AnnotationConfigWebApplicationContext#close()`), а он гарантированно вызывает `@PreDestroy`.

Payload, который расширение шлёт в Workato:

```json
{
  "message": "<as supplied at subscribe>",
  "generated_at": "2026-10-16T14:03:21.337Z",
  "tick": 1,
  "subscription_id": "<uuid>",
  "period_seconds": 10
}
```

## YAML-конфиги

**Тестовая фикстура** `src/test/resources/extensions/timer.yml`:

```yaml
extensions:
  timer:
    controller: com.mycompany.onprem.timer.TimerExtension
    min-period-seconds: 1
```

**Пример для `config.yml` в проде** (отдельно, не в тестах):

```yaml
extensions:
  timer:
    controller: com.mycompany.onprem.timer.TimerExtension
    min-period-seconds: 5
    webhook:
      retries: 5
      timeout-ms: 10000
```

## JUnit-тесты — `TimerExtensionTest`

**Файл:** `src/test/java/com/mycompany/onprem/timer/TimerExtensionTest.java`

Паттерн: `static AgentExtensionHarness agent = AgentExtensionHarness.extension("timer").yaml("extensions/timer.yml")`,
как в `SecurityExtensionTest.java:22`. `agent.webhookApi().reset()` в `@BeforeEach`. Используем
`agent.mockMvc().perform(post(agent.urlFor("/webhook/...")).content(...))` и `agent.webhookApi()` (
FakeAgentWebhookApiV1 — задокументирован в README-extension-test.md ~L313+; поддерживает
`.whenSent(url).thenReturn(status, body)`, `.allCalls()`, `.callsFor(url)`, `.lastCall()`, нулевые задержки ретраев).

Для асертов над телами ответов использовать общий `ObjectMapper` (новый экземпляр в тесте, конфигурация агентского
совпадать не обязана — только snake_case ключи записей). Для асертов над телом ушедшего webhook'а — `call.body()`
возвращает уже распарсенный `Map<String,Object>` (FakeAgentWebhookApiV1 использует общий Jackson), либо
`call.bodyAs(TimerEvent.class)` если такая форма документирована в README-extension-test.md (проверить при
имплементации; если нет, парсить через `mapper.convertValue(call.body(), TimerEvent.class)`).

Кейсы:

1. `subscribeReturnsIdUuid` — валидный body → 200, `SubscribeResponse.id()` соответствует UUID-regex.
2. `fireNowPostsExpectedPayload` — subscribe (period=5, чтобы scheduler не успел тикнуть),
   `.whenSent(url).thenReturn(200,"{}")`, `/fire_now`, проверить `agent.webhookApi().lastCall()`: `kind==SEND`, URL,
   десериализовать тело в `TimerEvent` и проверить все 5 полей (`message`, `tick==1`, `subscription_id`,
   `period_seconds==5`, ISO-8601 `generated_at`), заголовок `X-Workato-Event-Id` присутствует. Ответ `/fire_now` —
   `FireNowResponse(true, 200)`.
3. `fireNowUnknownIdReturns404` — `allCalls().isEmpty()`.
4. `subscribeBelowMinReturns400` — вложенный alt-harness с `property("min-period-seconds","10")`, `period_seconds=1` →
   400, тело `ErrorResponse.error()` содержит `"must be >="`.
5. `unsubscribeCancelsScheduledTask` — period=1, подождать `allCalls().size() >= 1` (deadline 3s), `/unsubscribe`,
   записать `n`, подождать 2.5s, `allCalls().size()==n`.
6. `contextShutdownStopsScheduler` — alt-harness
   `.extension("timer-alt").controller(TimerExtension.class).property("min-period-seconds","1").start()`, подписка
   period=1, подождать 1 вызов, `altHarness.stop()`, записать `n`, подождать 2s, `n` не изменилось (= `@PreDestroy`
   сработал).
7. `multipleSubscriptionsFireIndependently` — две подписки на `https://a/wh` и `https://b/wh`, `/fire_now` на каждую,
   `callsFor("https://a/wh").size()==1` и аналогично для b, `subscription_id` в теле каждого (как
   `TimerEvent.subscriptionId()`) совпадает со «своим».
8. (опционально) `swallowsRetryExhaustionInScheduledTick` — `.whenSent(url).thenFailAll()`, period=1, подождать ~3s,
   убедиться, что были попытки (`callsFor(url).size() > 0`) и ни одного `Exception` наружу — доказывает
   WARN-and-continue в `deliver(...)`.

## Ruby-коннектор — `timer`

**Файл:** `src/main/ruby/timer/connector.rb` (имя `connector.rb` обязательно, см. CLAUDE.md).

Структура — **peaz-стиль** (`workato-webhook-opa/workato-sdk/connector.rb`), а не security/ntlm-стиль, потому что
эндпоинтов 4 и повторять URL в каждом вызове — шум. Отличается от security/ntlm двумя важными моментами:

1. **`base_uri`** на уровне connection — все вызовы пишутся как `get('webhook/health')`,
   `post('webhook/subscribe').payload(...)` без повторения `http://localhost/ext/<profile>/`.
2. **
   `authorization: { type: 'custom_auth', acquire: lambda { {} }, apply: lambda { headers('X-Workato-Connector': 'enforce') } }`
   ** — заголовок добавляется автоматически к каждому запросу, не нужно писать `.headers(...)` в каждой лямбде.

Структура:

- `secure_tunnel: true` в корне (как в security/ntlm и peaz).
- **connection.fields**:
    - `profile` (string, required) — имя профиля расширения в `config.yml`.
    - `connection_id` (string, required) — **обязательно явное поле**: Workato SDK не прокидывает `connection_id` в
      webhook-лямбды сам, его читают из `connection['connection_id']`. peaz делает так же.
- **connection.base_uri**: `lambda { |connection| "http://localhost/ext/#{connection['profile']}/" }`.
- **connection.authorization**: `custom_auth` с `acquire: lambda { {} }` и
  `apply: lambda { headers('X-Workato-Connector': 'enforce') }`.
- **test lambda** —
  `get('webhook/health').after_error_response(/.*/) { |_c, body, _h, message| error("...#{message}...#{body}") }`. Hits
  `GET /webhook/health` → 200 (новый эндпоинт расширения, см. Java-секцию).
- **object_definitions.timer_event** — fields `message`, `generated_at` (date_time), `tick` (integer),
  `subscription_id`, `period_seconds` (integer). Нужен, чтобы `output_fields` триггера был декларативным.
- **actions.fire_now** — input: `subscription_id` (string, required).
  `post('webhook/fire_now').payload(subscription_id: input['subscription_id'])`. Output fields: `delivered` (boolean),
  `status_code` (integer).
- **triggers.tick** — тип webhook:
    - `input_fields`: `message` (string, required), `period_seconds` (integer, required, default 10, hint «minimum 5 in
      production»).
    - `webhook_subscribe: lambda do |webhook_url, connection, input, recipe_id|`
      `  post('webhook/subscribe').payload(workato_webhook_url: webhook_url, recipe_id: recipe_id, connection_id: connection['connection_id'], message: input['message'], period_seconds: input['period_seconds']).after_error_response(/.*/) { |_c, body, _h, m| error("Failed to subscribe: #{m}. #{body}") }`
      `end` — возвращает тело ответа расширения `{"id": "<uuid>"}` как `webhook_subscribe_output`. Именно ключ `id`: SDK
      фоллбечит `webhook_subscribe_output['id'] || webhook_subscribe_output['recipe_id']` в `/unsubscribe`.
    - `webhook_notification: lambda do |_input, payload, _ext_in, _ext_out, _headers, _params| payload end` — 6-арг
      форма (без `connection`), payload уже hash от SDK, прокидывается как event.
    - `webhook_unsubscribe: lambda do |webhook_subscribe_output, connection|`
      `  post('webhook/unsubscribe').payload(recipe_id: webhook_subscribe_output['id'], connection_id: connection['connection_id']).after_error_response(/.*/) { |_c, body, _h, m| error("Failed to unsubscribe: #{m}. #{body}") }`
      `end` — 2-арг сигнатура (`webhook_subscribe_output, connection`).
    - `dedup: lambda do |payload| "#{payload['subscription_id']}-#{payload['tick']}" end` — реально идемпотентный ключ (
      у peaz суффикс `Time.now.to_f` — анти-паттерн, который ломает дедуп, не копируем).
    - `output_fields: lambda { |object_definitions| object_definitions['timer_event'] }`.
    - `sample_output`: hash с демо-значениями всех пяти полей.

**Что из peaz НЕ переносим**: `custom_action: true` + `custom_action_help` (не нужно для демо),
`methods.required_extension_version` (это OEM-extensions конвенция, не наш случай), `listen_port` connection field (у
peaz — порт встроенного HttpServer, нам не нужен).

## Ruby-спека

**Файл:** `src/test/ruby/timer/spec/connector_spec.rb` + `src/test/ruby/timer/settings.yaml` +
`src/test/ruby/timer/spec/spec_helper.rb` (one-liner `require_relative`).

Шапка-комментарий — скопировать шаблон из `src/test/ruby/security/spec/connector_spec.rb:1-16`, подменив `security` →
`timer` и `:securityRspec` → `:timerRspec` (CLAUDE.md требует этот блок на каждом spec).

```ruby
CONNECTOR_PATH = File.expand_path('../../../../main/ruby/timer/connector.rb', __dir__).freeze

RSpec.describe 'onprem timer connector' do
  let(:settings)  { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }
```

`settings.yaml`:

```yaml
profile: timer
connection_id: test-conn
```

Кейсы:

1. `connector.test(settings)` под `VCR.use_cassette('test')` — не падает (кассета — `GET /ext/timer/webhook/health` →
   200).
2. `connector.actions.fire_now.execute(settings, 'subscription_id' => 'sub-abc')` →
   `{'delivered'=>true,'status_code'=>200}` (cassette `fire_now`).
3.
`connector.triggers.tick.webhook_subscribe('https://recipe/wh', settings, {'message'=>'hi','period_seconds'=>10}, 'recipe-xyz')`
под `VCR.use_cassette('subscribe')` — возвращает `{'id'=>...}`; в кассете body запроса содержит все 5 полей.
4.
`connector.triggers.tick.webhook_notification(nil, {'tick'=>1,'message'=>'hi','subscription_id'=>'sub-abc','period_seconds'=>10,'generated_at'=>'2026-10-16T14:03:21Z'}, nil, nil, {}, {})` —
assert возвращает полученный payload hash как есть (функция-тождество).
5. `connector.triggers.tick.webhook_unsubscribe({'id'=>'sub-abc'}, settings)` под `VCR.use_cassette('unsubscribe')` —
   204 без исключений; в кассете body содержит `recipe_id: sub-abc, connection_id: test-conn`.
6. `connector.triggers.tick.dedup({'subscription_id'=>'sub-abc','tick'=>7})` → `'sub-abc-7'` (детерминистично, без
   времени).

## VCR-кассеты

Положить в `src/test/resources/tape_library/timer/` (путь строит `src/test/ruby/spec_helper.rb` по basename CWD):

| Файл              | Запрос                                | Ответ                                      |
|-------------------|---------------------------------------|--------------------------------------------|
| `test.yml`        | `GET  /ext/timer/webhook/health`      | `200` (empty body)                         |
| `fire_now.yml`    | `POST /ext/timer/webhook/fire_now`    | `200 {"delivered":true,"status_code":200}` |
| `subscribe.yml`   | `POST /ext/timer/webhook/subscribe`   | `200 {"id":"0000-...-aaaa"}`               |
| `unsubscribe.yml` | `POST /ext/timer/webhook/unsubscribe` | `204` (empty body)                         |

Шаблон кассеты — скопировать с `src/test/resources/tape_library/security/sha256_digest_hello.yml`.

## `build.gradle` — новая задача

Добавить ровно по шаблону `:ntlmRspec` (`build.gradle:~200`):

```gradle
task timerRspec(type: Exec) {
    group = 'verification'
    description = rspecDescription('timer')
    dependsOn rubyBundleInstall
    workingDir file('src/test/ruby/timer')
    commandLine rspecCommand(project.findProperty('spec'))
}
```

И добавить к агрегатору:

```gradle
task testRubyConnectors {
    group = 'verification'
    description = 'Runs RSpec for every Ruby custom connector using VCR cassettes.'
    dependsOn securityRspec, ntlmRspec, timerRspec
}
```

Больше ничего менять не нужно — `rubyBundleInstall`, `rspecCommand(...)`, `rspecDescription(...)` переиспользуются как
есть; Jackson, Spring, `jakarta.annotation-api` уже на classpath'е расширения.

## Verification (как проверить end-to-end)

1. `./gradlew clean build` — должно собраться, Java 21.
2. `./gradlew :test --tests 'com.mycompany.onprem.timer.TimerExtensionTest'` — 7 (или 8) JUnit-тестов зелёные.
3. `./gradlew :timerRspec` — 5 RSpec-примеров зелёные, VCR не ругается на лишние/отсутствующие кассеты.
4. `./gradlew :timerRspec -Pspec='fire_now'` — работает фильтр `-Pspec`.
5. `./gradlew test` — весь скоуп (Java + Ruby security/ntlm/timer) зелёный, security/ntlm по-прежнему проходят (
   регрессии нет).
6. Smoke-деплой (вручную, не входит в автоматические тесты): собрать fat JAR, скопировать в
   `WORKATO_AGENT_HOME/lib_ext/`, добавить в `config.yml` блок из §YAML, перезапустить агент. В Workato-рецепте
   настроить триггер `tick` с `period_seconds=10, message="hi"`, убедиться, что каждые 10 секунд в history рецепта
   появляется запись с монотонно растущим `tick` и свежим `generated_at`.

## Критические файлы (новые / изменяемые)

- `src/main/java/com/mycompany/onprem/timer/TimerExtension.java` — новый, ~140 строк.
- `src/test/java/com/mycompany/onprem/timer/TimerExtensionTest.java` — новый.
- `src/test/resources/extensions/timer.yml` — новый.
- `src/main/ruby/timer/connector.rb` — новый.
- `src/test/ruby/timer/settings.yaml` — новый.
- `src/test/ruby/timer/spec/spec_helper.rb` — новый (one-liner).
- `src/test/ruby/timer/spec/connector_spec.rb` — новый.
- `src/test/resources/tape_library/timer/{test,fire_now,subscribe,unsubscribe}.yml` — 4 новые кассеты.
- `build.gradle` — +1 task, +1 dependency в агрегатор.

## Используемые существующие утилиты / паттерны (reuse)

- `AgentExtensionHarness` + `FakeAgentWebhookApiV1` — из `extension-test-34.0.jar`; документированы в
  `extension-api/README-extension-test.md`.
- DCL lazy init — `src/main/java/com/mycompany/onprem/ntlm/NtlmExtension.java:181` (`client()`).
- Alt-profile harness `.start()/.stop()` —
  `src/test/java/com/mycompany/onprem/security/SecurityExtensionTest.java:74-82`.
- Шаблон subscribe/unsubscribe контроллера — `extension-api/README-extension-api.md:271-328` (копируем структуру
  record'ов и `ConcurrentHashMap`).
- `rspecCommand/rspecDescription/rubyBundleInstall` — `build.gradle` (строки ~158-192).
- Шапка-комментарий для `.rb`-спеки — `src/test/ruby/security/spec/connector_spec.rb:1-16`.
- Шаблон VCR-кассеты — `src/test/resources/tape_library/security/sha256_digest_hello.yml`.
- Коммит/push делать не буду без явной команды (global rule из `~/.claude/CLAUDE.md`).
