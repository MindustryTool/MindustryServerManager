## ADDED Requirements

### Requirement: O(1) Session Lookup for Event Processing
`DailyTaskService` SHALL resolve sessions during high-frequency events (`BlockBuildEndEvent`, `UnitBulletDestroyEvent`) via direct key lookup `sessionService.get(uuid)` in O(1) time without performing linear iteration over active sessions.

#### Scenario: Bullet destroys unit
- **WHEN** `UnitBulletDestroyEvent` fires for an active player
- **THEN** the player's session is looked up in O(1) time via UUID key
- **AND** no iterator or anonymous predicate lambda is allocated
