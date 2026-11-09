# action-filters Specification

## Purpose
Fast dispatching and invocation of player action filters without per-action reflection allocations.

## Requirements

### Requirement: Pre-Resolved Action Filter Invocation
`ActionFilterManager` SHALL resolve method parameter types and `Registry` dependencies once at registration time. When player actions are processed, the filter SHALL NOT invoke `Method.getParameters()` or query `Registry.get` dynamically.

#### Scenario: Player places a block
- **WHEN** a player places or breaks a block and registered action filters execute
- **THEN** the action filter is invoked using pre-resolved arguments
- **AND** no `Method.getParameters()` array allocation occurs during the action
