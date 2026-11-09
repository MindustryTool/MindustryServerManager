# map-rating Specification

## Purpose

Persistent star-based map rating storage in SQLite, allowing players to rate maps from 1 to 5 stars, querying rating stats, and formatting display strings with colored breakdowns.
## Requirements
### Requirement: Database-backed map rating storage
The system SHALL persist map rating star counts, vote totals (`votes`), and average rating (`rating`) in the `map_ratings` SQLite database table keyed by `map_name`.

#### Scenario: Update map rating stores vote counts
- **WHEN** a player submits a valid star rating (between 1 and 5) for a map
- **THEN** the corresponding star counter (`star_1` through `star_5`) for that `map_name` is incremented in the `map_ratings` table
- **AND** `votes` and `rating` are updated accordingly

#### Scenario: Retrieve stat for unrated map
- **WHEN** ratings are requested for a map with no recorded votes
- **THEN** the system returns a rating stat with 0 votes and 0.0 rating

#### Scenario: Retrieve stat for rated map
- **WHEN** ratings are requested for an existing rated map
- **THEN** the system returns the persisted votes and calculated rating from the database

### Requirement: Formatted map rating display
The system SHALL format map rating information including author, description, star breakdown, and average rating for player menus and chat messages.

#### Scenario: Display string includes star breakdown
- **WHEN** `getDisplayString` is requested for a map with votes
- **THEN** the returned text contains each star level count and colored average rating

### Requirement: In-memory rating caching
The `MapRatingService` SHALL cache retrieved `MapRatingStat` values in memory to avoid redundant database reads.

#### Scenario: Cached stat retrieval on repeated query
- **WHEN** ratings for a map name are requested multiple times
- **THEN** the system returns the cached `MapRatingStat` without querying the database again

#### Scenario: Cache updated after vote submission
- **WHEN** a player submits a star rating for a map
- **THEN** the in-memory cache for that map is updated with the new vote counts and rating

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

