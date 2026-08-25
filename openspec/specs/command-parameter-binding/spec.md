# command-parameter-binding

## Purpose
TBD - created by archiving change add-command-error-handling. Update Purpose after archive.

## Requirements
### Requirement: Optional parameters default to null when omitted
When a command is invoked without an argument that maps to a `@Param(required = false)` parameter, the framework SHALL bind `null` to that parameter (for non-primitive types) and SHALL NOT fail command execution.

#### Scenario: Client command invoked without optional argument
- **WHEN** a player runs `/maps` with no arguments and `page` is declared as `@Param(name = "page", required = false) Integer page`
- **THEN** `mapParams` binds `null` to `page`, invocation succeeds, and no error is reported

#### Scenario: Optional argument provided
- **WHEN** a player runs `/maps 2`
- **THEN** `page` is bound to the converted value `2`

### Requirement: Commands handle absent optional arguments
Command implementations SHALL NOT throw when an optional parameter is bound to `null`; they MUST apply their own default handling (e.g. `/maps` defaults `page` to `0`).

#### Scenario: maps command with omitted page
- **WHEN** `/maps` executes with `page == null`
- **THEN** the command treats it as page `0` and returns the first page of results

### Requirement: Missing required arguments report annotation names
When a required argument is missing, the framework SHALL raise a `ParamException` whose message includes the annotated `@Param(name)` value rather than the reflective Java parameter name.

#### Scenario: Required argument omitted
- **WHEN** a player invokes a command omitting a required parameter annotated `@Param(name = "map")`
- **THEN** the player-facing error references "map", not a compiled name such as "arg0"

### Requirement: Argument ordering validation at registration
The framework SHALL continue to reject at registration time any method where an optional `@Param` precedes a required one or a variadic `@Param` is not last.

#### Scenario: Invalid ordering rejected
- **WHEN** a command declares `[optional]` followed by `<required>`
- **THEN** registration fails with an IllegalArgumentException describing the constraint
