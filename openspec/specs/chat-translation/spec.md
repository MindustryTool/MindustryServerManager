# chat-translation Specification

## Purpose
TBD - created by archiving change add-chat-translation. Update Purpose after archive.
## Requirements
### Requirement: Multi-Provider Ordered Fallback Interface
The plugin SHALL define a `TranslationProvider` interface with ordering and availability methods, and a `TranslationService` that tries providers in ascending order until one succeeds or all fail. `TranslationService` SHALL dynamically resolve all available `TranslationProvider` components registered in the system so that providers are available regardless of component initialization order.

#### Scenario: Primary provider succeeds
- **WHEN** a translation request is initiated and the primary provider succeeds
- **THEN** the translated result from the primary provider is returned

#### Scenario: Primary provider fails, falls back to backup
- **WHEN** the primary provider throws an exception or is unavailable and a backup provider is registered
- **THEN** the system attempts translation using the next available backup provider in order

#### Scenario: All providers fail
- **WHEN** all registered translation providers fail or are unavailable
- **THEN** the system gracefully falls back to returning the original message without crashing

#### Scenario: Lazy provider resolution
- **WHEN** translation is requested and providers have not yet been populated into the service
- **THEN** the service dynamically discovers registered `TranslationProvider` instances from `Registry`

### Requirement: Google Web Translation Provider
The plugin SHALL provide a `GoogleWebProvider` using Google's free web endpoint (`client=gtx`), parsing multi-segment JSON responses and unescaping HTML entities.

#### Scenario: Translate multi-segment message
- **WHEN** a multi-sentence message is sent to Google Web translation
- **THEN** all translated segments from the response array are concatenated into a single coherent text

#### Scenario: Unescape HTML entities
- **WHEN** the translation result contains HTML entities (such as `&#39;`, `&quot;`, or `&amp;`)
- **THEN** the provider decodes them into standard characters (`'`, `"`, `&`)

### Requirement: Asynchronous Chat Interception
The `ChatTranslation` component SHALL intercept non-command chat messages via `Vars.netServer.admins.addChatFilter`, return `null` synchronously to prevent blocking the game thread, and execute translation asynchronously.

#### Scenario: Message does not freeze server ticks
- **WHEN** a player sends a chat message that requires translation
- **THEN** the filter returns null immediately and the translation executes in a non-blocking background thread without dropping server TPS

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
The plugin SHALL maintain a Caffeine in-memory cache for translations keyed by cleaned text and target language code.

#### Scenario: Repeated chat message hits cache
- **WHEN** a message has already been translated to a specific target language within the cache TTL
- **THEN** the translation is resolved directly from cache without making an external HTTP request

