## MODIFIED Requirements

### Requirement: Google Web Translation Provider
The server manager SHALL provide a `GoogleWebProvider` implementing `TranslationProvider` using Google's free web endpoint (`client=gtx`), parsing multi-segment JSON responses, unescaping HTML entities, managing progressive backoff cooldown, and supporting optional injection of `MultiSourceProxyPool` via a long-lived HTTP client with a dynamic `ProxySelector`.

#### Scenario: Direct execution when no proxy pool injected
- **WHEN** `GoogleWebProvider` is configured without a proxy pool
- **THEN** requests are dispatched via the shared long-lived HTTP client

#### Scenario: Proxied execution using long-lived client with dynamic proxy selector
- **WHEN** `GoogleWebProvider` is configured with a `MultiSourceProxyPool`
- **THEN** requests are dispatched through a single long-lived proxied HTTP client backed by the pool's dynamic `ProxySelector` without allocating new HTTP client instances per request or retry attempt

#### Scenario: Translate multi-segment message
- **WHEN** a multi-sentence message is sent to Google Web translation
- **THEN** all translated segments from the response array are concatenated into a single coherent text

#### Scenario: Unescape HTML entities
- **WHEN** the translation result contains HTML entities (such as `&#39;`, `&quot;`, or `&amp;`)
- **THEN** the provider decodes them into standard characters (`'`, `"`, `&`)

#### Scenario: Progressive cooldown on error
- **WHEN** an HTTP 429, 5xx, or network error occurs
- **THEN** the provider activates a progressive backoff cooldown starting at 5 seconds and exponentially increasing up to 5 minutes
