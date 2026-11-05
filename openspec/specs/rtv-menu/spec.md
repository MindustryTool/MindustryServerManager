# rtv-menu Specification

## Purpose

Provide deterministic ordering and rendering for candidate maps in the rock-the-vote (RTV) selection menu without database-driven sorting side effects.

## Requirements

### Requirement: Deterministic map sorting
The `RtvMenu` SHALL sort available maps deterministically using precomputed stats without executing database queries during comparator evaluations.

#### Scenario: Unrated maps placed first
- **WHEN** candidate maps include unrated maps (0 votes or 0.0 rating) and rated maps
- **THEN** unrated maps are prioritized at the top of the menu before rated maps

#### Scenario: Ties broken by votes then name
- **WHEN** two candidate maps have identical rating scores
- **THEN** the map with higher total votes is placed first
- **AND** if total votes are equal, the maps are ordered alphabetically by name case-insensitively

#### Scenario: Pure comparator without database queries
- **WHEN** sorting candidate maps in `RtvMenu`
- **THEN** comparator evaluations use snapshot values without querying the database
