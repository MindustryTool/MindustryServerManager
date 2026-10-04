# chat-translation Specification

## Purpose
Translates in-game chat messages across player locales using server-side translation providers and delivers formatted translations asynchronously to recipients.

## Requirements
### Requirement: Multi-Provider Ordered Fallback Interface
The server manager SHALL define a `TranslationProvider` interface with ordering and availability methods, and a `TranslationService` that evaluates registered providers in ascending order. If an available provider throws an exception during translation, the system SHALL fail immediately without attempting subsequent providers.

#### Scenario: Primary provider succeeds
- **WHEN** a translation request is initiated and the primary provider succeeds
- **THEN** the translated result from the primary provider is returned

#### Scenario: Primary provider in cooldown, next available provider used
- **WHEN** the primary provider is currently unavailable or in cooldown and a backup provider is available
- **THEN** the system attempts translation using the next available provider

#### Scenario: Active provider fails during translation
- **WHEN** an active provider is selected and throws an exception during translation
- **THEN** the system fails immediately without attempting remaining providers, logs the error, and returns null

#### Scenario: All providers unavailable
- **WHEN** all registered translation providers are in cooldown or unavailable
- **THEN** the system returns null gracefully without crashing

### Requirement: Google Web Translation Provider
The server manager SHALL provide a `GoogleWebProvider` implementing `TranslationProvider` using Google's free web endpoint (`client=gtx`), parsing multi-segment JSON responses, unescaping HTML entities, and managing progressive backoff cooldown.

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
