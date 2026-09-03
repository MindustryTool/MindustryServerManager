# login-management Specification

## Purpose

Centralized management of player authentication login links with a strict 5-minute expiration policy and reusable interactive `LoginMenu` dialogs.

## Requirements
### Requirement: Login URL 5-minute expiration policy
The system SHALL enforce that player login URLs expire exactly 5 minutes (300 seconds) from the moment they are generated.

#### Scenario: Valid login link within 5 minutes
- **WHEN** a player requests a login link and an active link generated less than 5 minutes ago exists
- **THEN** the system reuses the valid link without creating duplicate authentication sessions

#### Scenario: Expired login link regeneration
- **WHEN** a player requests a login link or clicks login in the UI more than 5 minutes after link generation
- **THEN** the system treats the previous link as expired
- **AND** automatically requests a fresh login URL with a renewed 5-minute expiration window

### Requirement: Reusable interactive LoginMenu UI
The system SHALL provide a reusable UI menu dialog prompting unauthenticated players to log in.

#### Scenario: Displaying login menu
- **WHEN** an unauthenticated player is directed to log in (e.g. from votekick or grief detection)
- **THEN** the system displays `LoginMenu` with a clear explanation and an interactive button to open the authentication URL

#### Scenario: Interacting with login option
- **WHEN** the player clicks "Login" in the `LoginMenu`
- **THEN** the system verifies link freshness, refreshes if expired, and opens the URI on the player client via `Call.openURI`

