# command-error-handling

## Purpose
TBD - created by archiving change add-command-error-handling. Update Purpose after archive.

## Requirements
### Requirement: Command exceptions are unwrapped before reporting
When an annotated client or server command handler throws, the command handlers SHALL unwrap `java.lang.reflect.InvocationTargetException` (recursively, via `getCause()`) and use the root cause as the reported exception. If the cause chain yields null, the original throwable SHALL be used.

#### Scenario: Handler throws wrapped exception
- **WHEN** a command method throws a `NullPointerException` that `Method.invoke` wraps in `InvocationTargetException`
- **THEN** the reported exception is the `NullPointerException`, not the wrapper

#### Scenario: Wrapped exception with null message
- **WHEN** the unwrapped root cause has a null message (e.g. bare NPE)
- **THEN** the reported detail falls back to the exception class simple name

### Requirement: Player receives localized error detail on client command failure
When a client command handler throws an unexpected exception, the system SHALL send the executing player a localized message (via the `commands.error` translation key in the player's locale) containing a short description of the root cause.

#### Scenario: Player runs a failing command
- **WHEN** a player executes `/maps` and the handler throws
- **THEN** the player receives a localized "[scarlet]"-prefixed error message including the root cause description

#### Scenario: Locale-specific message
- **WHEN** a player with locale `ru` triggers a command failure
- **THEN** the error message is rendered from the `commands.error` key of the Russian catalog

### Requirement: Console logs concise root-cause errors
On command failure, both handlers SHALL log to console using `arc.util.Log.err` with the command name, the invoking player name for client commands, and the unwrapped root cause — so the printed stack trace starts at the actual failure site without reflective wrapper frames.

#### Scenario: Client command failure logging
- **WHEN** player "Alice" runs a client command whose handler throws
- **THEN** the console log identifies the command name, the player name, and shows the stack trace of the root cause

#### Scenario: Server command failure logging
- **WHEN** a server (console) command handler throws
- **THEN** the console log identifies the command name and shows the stack trace of the root cause

### Requirement: Parameter errors keep dedicated handling
The handlers SHALL continue to catch `plugin.commands.ParamException` separately and report it through the existing `commands.param_error` flow, without treating it as an unexpected failure.

#### Scenario: Invalid parameter value
- **WHEN** a player passes a non-numeric value to a command parameter typed as Integer
- **THEN** the player receives the `commands.param_error` message and no unexpected-failure error is logged

### Requirement: Error translations exist in all supported locales
The `commands.error` translation key SHALL exist in every locale file under `plugin/src/main/resources/i18n/` and SHALL include a `{message}` placeholder for the root-cause description.

#### Scenario: Key parity across locales
- **WHEN** any of the 11 i18n JSON files is loaded
- **THEN** its `commands.error` entry exists and contains the `{message}` placeholder
