## MODIFIED Requirements

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
