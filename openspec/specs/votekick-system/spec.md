# votekick-system Specification

## Purpose

Provides interactive UI menus and chat commands (`/votekick`, `/vote`) for initiating and voting on player kicks, complete with categorized kick reasons, AFK player exclusion, team isolation in PvP, cooldown management, and non-reflective integration with Mindustry's networking server.
## Requirements
### Requirement: Interactive votekick initiation via UI Menu
The system SHALL provide a multi-step UI menu for initiating a votekick against a disruptive player without requiring manual chat syntax, requiring the initiator to be an authenticated user.

#### Scenario: Opening votekick menu with eligible targets
- **WHEN** an eligible authenticated player executes `/votekick` with no arguments
- **THEN** the system displays a menu showing all eligible players to kick (excluding self, admins, and opposing team members in PvP)
- **AND** displays a close option

#### Scenario: Unauthenticated player attempts to initiate votekick
- **WHEN** a player who is not logged in attempts to start a votekick or open the votekick menu
- **THEN** the system rejects the votekick action
- **AND** sends a localized message that authentication is required
- **AND** presents the login UI menu to prompt authentication

#### Scenario: Less than minimum player threshold
- **WHEN** a player tries to open the votekick menu or start a vote when fewer than 3 players are connected
- **THEN** the system rejects the request and sends a localized message indicating at least 3 players are required

#### Scenario: Selecting a target and kick reason
- **WHEN** the initiator selects a target player from the list
- **THEN** the system presents a reason selection menu containing standard reason options (such as Griefing, Inactivity/AFK, Disruptive Behavior, Other)
- **AND** confirming a reason initiates the votekick session

#### Scenario: Target cannot be kicked
- **WHEN** an attempt is made to target self, an admin, or an opponent team member in PvP
- **THEN** the system rejects the action with an appropriate localized error message

#### Scenario: Votekick cooldown active
- **WHEN** a player who initiated a votekick attempts to start another vote before the cooldown period expires (5 minutes)
- **THEN** the system prevents vote creation and informs the player of the remaining cooldown time

### Requirement: Interactive in-game voting UI prompt
The system SHALL present an interactive vote prompt menu to all eligible voters when a votekick session is active.

#### Scenario: Receiving vote menu prompt
- **WHEN** a votekick session starts
- **THEN** an interactive menu is sent to eligible team members displaying target name, initiator name, reason, vote tally, and time remaining
- **AND** options are presented for "Agree (Yes)" and "Disagree (No)"

#### Scenario: Admin vote options in menu
- **WHEN** an administrator views the active vote prompt menu
- **THEN** the menu includes an additional option to "Cancel Vote"

#### Scenario: Voting via menu
- **WHEN** a player selects "Agree" or "Disagree" in the prompt menu
- **THEN** their vote is recorded, the vote tally is updated, and session pass conditions are evaluated immediately

### Requirement: Backwards-compatible chat command interface
The system SHALL support existing `/votekick [player] [reason...]` and `/vote <y/n/c>` commands alongside the UI menus.

#### Scenario: Initiating votekick with arguments
- **WHEN** a player executes `/votekick <player> <reason...>` with a valid player identifier and reason text
- **THEN** a votekick session against the target with that reason is started immediately without opening the player selection menu

#### Scenario: Casting vote via chat command
- **WHEN** a player executes `/vote y` or `/vote n` while a votekick session is active
- **THEN** their vote is registered identical to selecting the corresponding option in the UI menu

#### Scenario: Admin cancelling vote via chat command
- **WHEN** an administrator executes `/vote c`
- **THEN** the active votekick session is immediately cancelled and announced to all players

#### Scenario: Duplicate or invalid vote command
- **WHEN** a player attempts to vote multiple times with the same sign or votes on their own trial
- **THEN** the command rejects the vote with a descriptive localized error message

### Requirement: Non-reflective vanilla votekick replacement
The system SHALL replace vanilla Mindustry votekick and vote commands without Java reflection or private field manipulation.

#### Scenario: Command deregistration and replacement on startup
- **WHEN** client commands are registered on server start
- **THEN** vanilla "votekick" and "vote" commands are removed from `netServer.clientCommands` using public `CommandHandler.removeCommand`
- **AND** custom `@ClientCommand` handlers for "votekick" and "vote" are registered in their place

### Requirement: AFK exclusion and threshold calculation
The system SHALL integrate with `vote-afk-exclusion` to ensure idle players do not inflate requirements or count towards passing votes.

#### Scenario: Vote threshold based on active sessions
- **WHEN** computing required votes for a votekick session
- **THEN** the required threshold is calculated from active, non-AFK eligible players (`sessionService.countActive()` / active teammates in PvP)
- **AND** clamped to a minimum required threshold (at least 2 votes)

#### Scenario: AFK votes excluded from count
- **WHEN** evaluating whether a votekick passes
- **THEN** stored votes from sessions currently flagged as AFK are excluded from the positive vote count

### Requirement: Session lifecycle, timeout, and ban enforcement
The system SHALL manage the entire lifecycle of a votekick session safely and deterministically.

#### Scenario: Vote succeeds
- **WHEN** the positive vote count reaches or exceeds the required threshold
- **THEN** the system broadcasts a localized success message
- **AND** kicks the target player with `KickReason.vote` and applies a server ban for the configured kick duration (60 minutes)
- **AND** cleans up the session

#### Scenario: Vote times out
- **WHEN** the voting duration (30 seconds) expires without meeting the required threshold
- **THEN** the system announces vote failure and resets the active session

#### Scenario: Target disconnects during vote
- **WHEN** the target player disconnects while a vote against them is active
- **THEN** the active votekick session is immediately concluded and cleaned up

