# chat-translation Specification

## Purpose
Translates in-game chat messages across player locales using server-side translation providers and delivers formatted translations asynchronously to recipients.

## Requirements
### Requirement: Multi-Provider Ordered Fallback Interface
The server manager SHALL define a `TranslationProvider` interface with name, availability, and translation execution methods, and a `TranslationService` that allows registering providers with explicit tier parameters. The service SHALL organize registered providers into tiers and balance requests within each tier using round-robin rotation. When a selected provider fails (throws an exception or returns null/blank text), the service SHALL retry up to 2 more times using up to 2 other available providers (capped at 3 total attempts per request) before returning null. The service SHALL try remaining available providers in the current tier first, and if exhausted, cascade to subsequent tiers. Round-robin indexing SHALL advance on initial tier selection per request and SHALL NOT skip positions due to internal retry attempts.

#### Scenario: Round-robin rotation within tier
- **WHEN** multiple providers in the same tier are available
- **THEN** successive cache-miss translation requests alternate between the available providers in round-robin order

#### Scenario: Retries do not perturb round-robin index
- **WHEN** a provider in a tier fails and an intra-tier retry executes
- **THEN** the subsequent independent translation request selects the next provider in the normal round-robin sequence without index jumping

#### Scenario: Provider in cooldown bypassed in round-robin
- **WHEN** one provider in a tier is in cooldown and another provider in the same tier is available
- **THEN** translation requests route exclusively to the available provider

#### Scenario: All providers in tier in cooldown falls back to next tier
- **WHEN** all providers in a lower tier are unavailable or in cooldown
- **THEN** the system evaluates available providers in the next tier

#### Scenario: Active provider failure retries with another provider in same tier
- **WHEN** the primary provider selected in a tier fails or returns blank text
- **THEN** the system retries translation with another available provider in the same tier without returning null immediately

#### Scenario: Tier exhaustion retries with provider in next tier
- **WHEN** all available providers in the initial tier fail during retries
- **THEN** the system escalates to available providers in the next tier up to a total of 3 attempts

#### Scenario: Capped at maximum 3 attempts
- **WHEN** 3 providers have been attempted and all 3 fail
- **THEN** the system stops retrying, logs a warning, and returns null

#### Scenario: All providers unavailable
- **WHEN** all registered translation providers across all tiers are in cooldown or unavailable
- **THEN** the system returns null gracefully without crashing

### Requirement: Translation Provider Streak Tracking and Transition Logging
The `TranslationService` SHALL track consecutive successful translations and consecutive failed translations per registered provider, and SHALL emit `Log.info` logs when a provider transitions into a failing streak or transitions into a succeeding streak.

#### Scenario: Provider transitions from success to failure
- **WHEN** a provider with previous consecutive successes fails a translation request
- **THEN** the service resets consecutive successes, starts consecutive failures at 1, and logs `Log.info` indicating the provider started failing

#### Scenario: Provider transitions from failure to recovery
- **WHEN** a provider with previous consecutive failures successfully completes a translation request
- **THEN** the service resets consecutive failures, starts consecutive successes at 1, resets cooldown, and logs `Log.info` indicating the provider started succeeding

### Requirement: Centralized Provider Cooldown and Backoff
The `TranslationService` SHALL centrally manage provider availability and progressive exponential backoff cooldowns based on consecutive failures, bypassing cooling-down providers until their cooldown duration elapses.

#### Scenario: Progressive backoff cooldown triggered on provider failure
- **WHEN** a provider experiences consecutive failures
- **THEN** the service marks the provider cooling down for a duration of 5 seconds scaled exponentially up to 5 minutes
- **AND** subsequent translation requests bypass the cooling down provider during that window

### Requirement: Lingva Translation Provider
The server manager SHALL provide a `LingvaProvider` implementing `TranslationProvider` using the Lingva API endpoint (`https://lingva-api.onrender.com/api/v1/auto/{target}/{query}`), parsing JSON responses into `TranslationResponseDto`.

#### Scenario: Translate plain text query
- **WHEN** a plain text message is sent to `LingvaProvider`
- **THEN** the provider encodes the query, sends a GET request to `/api/v1/auto/{target}/{query}`, and extracts the `translation` text and detected source language from `info.detectedSource`

### Requirement: Google Web Translation Provider
The server manager SHALL provide a `GoogleWebProvider` implementing `TranslationProvider` using Google's free web endpoint (`client=gtx`), parsing multi-segment JSON responses, unescaping HTML entities, and supporting optional injection of `MultiSourceProxyPool` via a long-lived HTTP client with a dynamic `ProxySelector`. When a proxy pool is injected, the system SHALL route each attempt to the usable proxy with the smallest sent count (tie by first in list order), SHALL track consecutive failures per proxy and evict after 3 consecutive failures, SHALL reset a proxy's failure count on success, SHALL refill asynchronously when usable proxies drop to 3 or fewer with debounce, SHALL fetch once asynchronously after pool creation, and SHALL fail fast to the next provider when no usable proxy exists without blocking on fetch.

#### Scenario: Direct execution when no proxy pool injected
- **WHEN** `GoogleWebProvider` is configured without a proxy pool
- **THEN** requests are dispatched via the shared long-lived HTTP client

#### Scenario: Proxied execution using long-lived client with dynamic proxy selector
- **WHEN** `GoogleWebProvider` is configured with a `MultiSourceProxyPool`
- **THEN** requests are dispatched through a single long-lived proxied HTTP client backed by the pool's dynamic `ProxySelector` without allocating new HTTP client instances per request or retry attempt
- **AND** the proxy actually used for the request equals the pool-picked proxy tracked for stats

#### Scenario: Least-sent proxy selection
- **WHEN** a proxied translation attempt starts with multiple usable proxies
- **THEN** the system picks the usable proxy with the smallest sent count, breaking ties by first in list order, and increments its sent count

#### Scenario: Consecutive failure eviction and retry
- **WHEN** a proxied attempt fails by timeout, connect error, or non-200 response
- **THEN** the system increments that proxy's consecutive failure count and retries with the next least-sent usable proxy up to 3 attempts per translation
- **AND** a proxy reaching 3 consecutive failures is evicted

#### Scenario: Success resets failure count
- **WHEN** a proxied attempt succeeds with HTTP 200 and valid body
- **THEN** that proxy's consecutive failure count resets to 0 and the proxy stays usable

#### Scenario: New proxies start at zero
- **WHEN** a refill fetch adds new proxies
- **THEN** new proxies start with sent count 0 while existing proxies keep their counts

#### Scenario: Low-watermark async refill with debounce
- **WHEN** usable proxy count drops to 3 or fewer
- **THEN** the system triggers one async refill guarded by a strict 2-minute debounce including when count is 0

#### Scenario: Eager async fetch after creation
- **WHEN** a `MultiSourceProxyPool` is created
- **THEN** the system triggers one async fetch without blocking the creator

#### Scenario: Fail fast when no usable proxy
- **WHEN** a proxied translation starts with zero usable proxies
- **THEN** the provider throws at once so `TranslationService` cascades to the next provider without waiting for fetch

#### Scenario: Translate multi-segment message
- **WHEN** a multi-sentence message is sent to Google Web translation
- **THEN** all translated segments from the response array are concatenated into a single coherent text

#### Scenario: Unescape HTML entities
- **WHEN** the translation result contains HTML entities (such as `&#39;`, `&quot;`, or `&amp;`)
- **THEN** the provider decodes them into standard characters (`'`, `"`, `&`)

### Requirement: Asynchronous Chat Interception
The plugin `ChatTranslation` component SHALL intercept non-command chat messages via `Vars.netServer.admins.addChatFilter`, immediately deliver the untranslated message to the sender, return `null` synchronously to prevent blocking the game thread, and execute translation asynchronously by querying `ApiGateway` directly.

#### Scenario: Message does not freeze server ticks
- **WHEN** a player sends a chat message that requires translation
- **THEN** the filter returns null immediately and the translation executes in a non-blocking background thread without dropping server TPS

#### Scenario: Sender receives immediate echo
- **WHEN** a player sends a chat message
- **THEN** the sender receives the formatted message immediately on the game thread without waiting for translation

#### Scenario: Gateway disconnected bypasses translation
- **WHEN** `ApiGateway` is disconnected or unavailable
- **THEN** translation is skipped immediately and the original message is delivered to recipients without timeout delay

#### Scenario: Command bypass
- **WHEN** a player message begins with `/`
- **THEN** the filter returns the message without intercepting or modifying it

### Requirement: Color Stripping Prior to Translation
The plugin SHALL strip all Mindustry color tags (`Strings.stripColors`) from the message before passing the text to any translation provider.

#### Scenario: Message contains color tags
- **WHEN** a player message contains color tags such as `[red]` or `[#ffffff]`
- **THEN** only the stripped plain text is passed to the translation API

### Requirement: Per-Recipient Locale Delivery & Formatting
The plugin SHALL deliver chat messages to recipients with the sender's player name and a colon `:` prepended to the message. If the recipient's language differs from the detected source language, the message SHALL append the translated text formatted as ` ([#00ff00]<translated>])`.

#### Scenario: Recipient language differs from source
- **WHEN** the detected source language is different from the recipient player's language
- **THEN** the recipient receives `<sender.name>: <original> ([#00ff00]<translated>])`

#### Scenario: Recipient language matches source
- **WHEN** the detected source language matches the recipient player's language
- **THEN** the recipient receives `<sender.name>: <original>` without a translation bracket

### Requirement: Translation In-Memory Caching
The server manager SHALL maintain a Caffeine in-memory cache for translations keyed by target language and text.

#### Scenario: Repeated chat message hits cache
- **WHEN** a message has already been translated to a specific target language within the cache TTL
- **THEN** the translation is resolved directly from cache without querying any provider

### Requirement: Per-User Translation Rate Limiting
The plugin `ChatTranslation` chat filter SHALL gate translation per player using a keyed token bucket (capacity 5, refill 1 token per second) keyed by the sender's player UUID, consuming one token per chat message before language fan-out. When a message is denied, the plugin SHALL skip translation for that message but SHALL still deliver the original untranslated message to the sender and recipients.

#### Scenario: Under-limit message is translated
- **WHEN** a player sends a chat message while their token bucket has tokens available
- **THEN** translation proceeds as before and translated messages are delivered per recipient locale

#### Scenario: Burst exhaustion skips translation but delivers original
- **WHEN** a player sends more messages than the burst capacity within the refill window
- **THEN** the denied messages are delivered untranslated to all recipients and no translate requests are sent for them

#### Scenario: Bucket refills over time
- **WHEN** a throttled player waits long enough for a token to refill
- **THEN** their next message is translated again

### Requirement: Per-Server Translation Rate Limiting
The backend `translate` RPC handler SHALL gate translation per server using a keyed token bucket (capacity 50, refill 10 tokens per second) keyed by the connecting server UUID, consuming one token per translate RPC call. When a call is denied, the handler SHALL return null without invoking `TranslationService`.

#### Scenario: Under-limit call is served
- **WHEN** a server sends a translate call while its token bucket has tokens available
- **THEN** the handler delegates to `TranslationService` and returns its result

#### Scenario: Over-limit call is rejected cheaply
- **WHEN** a server exceeds its burst capacity within the refill window
- **THEN** the handler returns null and `TranslationService.translate` is not called for that request

#### Scenario: Per-server buckets are independent
- **WHEN** one server exhausts its translation budget
- **THEN** another server's translate calls continue to be served

### Requirement: Bing Web Translation Provider
The server manager SHALL provide a `BingWebProvider` implementing `TranslationProvider`. It SHALL dynamically extract authentication parameters (`IG`, `IID`, `key`, `token`) from `https://www.bing.com/translator`, submit translation requests to `https://www.bing.com/ttranslatev3`, unescape HTML entities, and return a `TranslationResponse`. It SHALL cache authentication tokens until expiration and invalidate the cached session when receiving an expired or invalid token response.

#### Scenario: Successful translation request
- **WHEN** valid text and target language are requested
- **THEN** the provider extracts or reuses session tokens, issues a POST request to Bing translator endpoint, and returns the translated text in `TranslationResponse`

#### Scenario: Token expiration and refresh
- **WHEN** the session token expires or Bing returns a token error
- **THEN** the provider invalidates the existing token session and requests fresh session credentials before retrying

#### Scenario: Invalid response format or Bing failure
- **WHEN** Bing returns an unexpected status code or response format
- **THEN** the provider throws a descriptive exception allowing the translation service to trigger cooldown and fallback
