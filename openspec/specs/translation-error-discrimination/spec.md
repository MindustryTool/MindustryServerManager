# translation-error-discrimination Specification

## Purpose
TBD - created by archiving change translation-resilience-sqlite-l2. Update Purpose after archive.
## Requirements
### Requirement: Differentiating Temporary and Permanent Translation Errors
The system SHALL classify translation errors into temporary errors and permanent errors to control provider cooldown behavior.

#### Scenario: Temporary error triggers provider cooldown
- **WHEN** an upstream provider fails with a temporary error (such as HTTP 429 Too Many Requests, HTTP 5xx, or network connection/read timeout)
- **THEN** the failure is recorded in `ProviderState` as a temporary failure
- **AND** the provider's consecutive failure counter is incremented
- **AND** exponential cooldown backoff is applied

#### Scenario: Permanent client error does not trigger cooldown
- **WHEN** a translation request fails with a permanent error (such as HTTP 400 Bad Request, unsupported language code, or empty result without outage)
- **THEN** the attempt is recorded in metrics
- **AND** the provider is NOT placed into cooldown
- **AND** the next available provider is attempted if retry quota remains

