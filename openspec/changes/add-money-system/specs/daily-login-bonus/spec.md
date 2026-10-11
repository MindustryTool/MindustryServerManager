## MODIFIED Requirements

### Requirement: Daily login bonus on new-day login

When a `SessionCreatedEvent` fires (player joined), the plugin SHALL compare their last login date with today's date:
- if a record exists and the date differs from today, the plugin SHALL update the login date to today, grant **3600 exp** and **500 coins** to the player's stored counters, and send the player a message stating the daily login bonus;
- if a record exists and the date equals today, the plugin SHALL take no action.

The bonus SHALL be applied to the stored counters before the session's level and wallet settle, so the player's profile reflects the bonus immediately.

#### Scenario: Returning player logs in on a new day
- **WHEN** a `SessionCreatedEvent` fires for a player whose last login was on a previous day
- **THEN** their `data.exp` increases by 3600, their `data.money` increases by 500, the `player_logins` date is updated to today, and the player receives a daily login bonus message

#### Scenario: Returning player logs in twice the same day
- **WHEN** a `SessionCreatedEvent` fires for a player who already joined today
- **THEN** no bonus is granted and the stored login date is unchanged
