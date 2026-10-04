# chat-translation Specification

## Purpose
Translates in-game chat messages across player locales using server-side translation providers and delivers formatted translations asynchronously to recipients.

## Requirements
### Requirement: Multi-Provider Ordered Fallback Interface
The server manager SHALL define a `TranslationProvider` interface with name, availability, and translation execution methods, and a `TranslationService` that allows registering providers with explicit tier and order parameters. The service SHALL organize registered providers into tiers and balance requests within each tier using round-robin rotation. If an available provider throws an exception during translation, the system SHALL fail immediately without attempting subsequent providers.

#### Scenario: Round-robin rotation within tier
- **WHEN** multiple providers in the same tier are available
- **THEN** successive cache-miss translation requests alternate between the available providers in round-robin order

#### Scenario: Provider in cooldown bypassed in round-robin
- **WHEN** one provider in a tier is in cooldown and another provider in the same tier is available
- **THEN** translation requests route exclusively to the available provider

#### Scenario: All providers in tier in cooldown falls back to next tier
- **WHEN** all providers in a lower tier are unavailable or in cooldown
- **THEN** the system evaluates available providers in the next tier

#### Scenario: Active provider fails during translation
- **WHEN** an active provider is selected and throws an exception during translation
- **THEN** the system fails immediately without attempting remaining providers, logs the error, and returns null

#### Scenario: All providers unavailable
- **WHEN** all registered translation providers across all tiers are in cooldown or unavailable
- **THEN** the system returns null gracefully without crashing

### Requirement: Lingva Translation Provider
The server manager SHALL provide a `LingvaProvider` implementing `TranslationProvider` using the Lingva API endpoint (`https://lingva-api.onrender.com/api/v1/auto/{target}/{query}`), parsing JSON responses into `TranslationResponseDto`, and managing progressive backoff cooldown on failure.

#### Scenario: Translate plain text query
- **WHEN** a plain text message is sent to `LingvaProvider`
- **THEN** the provider encodes the query, sends a GET request to `/api/v1/auto/{target}/{query}`, and extracts the `translation` text and detected source language from `info.detectedSource`

#### Scenario: Progressive cooldown on error
- **WHEN** an HTTP 429, 5xx, or network error occurs during a Lingva request
- **THEN** the provider activates a progressive backoff cooldown starting at 5 seconds and exponentially increasing up to 5 minutes

### Requirement: Google Web Translation Provider
The server manager SHALL provide a `GoogleWebProvider` implementing `TranslationProvider` using Google's free web endpoint (`client=gtx`), parsing multi-segment JSON responses, unescaping HTML entities, managing progressive backoff cooldown, and supporting optional injection of `MultiSourceProxyPool` for proxied execution.

#### Scenario: Direct execution when no proxy pool injected
- **WHEN** `GoogleWebProvider` is configured without a proxy pool
- **THEN** requests are dispatched directly via standard `HttpClient`

#### Scenario: Proxied execution when proxy pool injected
- **WHEN** `GoogleWebProvider` is configured with a `MultiSourceProxyPool`
- **THEN** requests are tunneled through rotated proxies from the pool with automatic candidate retry and dead proxy eviction

#### Scenario: Translate multi-segment message
- **WHEN** a multi-sentence message is sent to Google Web translation
- **THEN** all translated segments from the response array are concatenated into a single coherent text

#### Scenario: Unescape HTML entities
- **WHEN** the translation result contains HTML entities (such as `&#39;`, `&quot;`, or `&amp;`)
- **THEN** the provider decodes them into standard characters (`'`, `"`, `&`)

#### Scenario: Progressive cooldown on error
- **WHEN** an HTTP 429, 5xx, or network error occurs
- **THEN** the provider activates a progressive backoff cooldown starting at 5 seconds and exponentially increasing up to 5 minutes

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
