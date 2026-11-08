## ADDED Requirements

### Requirement: Startup Preloading of Map Ratings
`MapRatingService` SHALL preload all rating records from the `map_ratings` database table into an in-memory cache upon server startup and world change, ensuring that operations iterating over map pools (such as `RtvMenu` generation and `/topmaps`) read exclusively from memory without querying SQLite on the main game thread.

#### Scenario: RTV menu opens
- **WHEN** a player opens `/rtv` with many maps in rotation
- **THEN** all map rating stats are retrieved from the preloaded in-memory cache
- **AND** no database queries are executed on the main game thread

#### Scenario: Asynchronous vote persistence
- **WHEN** a player submits a map rating
- **THEN** the in-memory cache is updated immediately
- **AND** database persistence runs asynchronously in `Tasks.io`
