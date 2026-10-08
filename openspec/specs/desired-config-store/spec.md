# desired-config-store

## Purpose

Store-only desired-config persistence on the manager with lazy healing via config-carrying calls. Synced from change update-config-manager.

## Requirements

### Requirement: Store-only desired-config persistence

The manager SHALL persist the full desired `ServerConfig` per server on local disk through the existing per-server file machinery and acknowledge `update-config` fast. The stored record SHALL keep the gateway `jwt` heal behavior on read fail. The handler SHALL never start, stop, or restart any process and SHALL perform no credit logic.

#### Scenario: Store without touching the process
- **WHEN** the manager receives a valid `update-config{serverId, config}`
- **THEN** it atomically persists the full config and replies success with no process state change

#### Scenario: Invalid payload stores nothing and fails loud
- **WHEN** the `update-config` payload fails validation
- **THEN** the manager replies `response-error` with the detail and leaves any previously stored config untouched

### Requirement: Config-carrying calls heal the store

The manager SHALL overwrite its stored full desired config at the top of the `get-mismatch` and `host-server` handlers before diffing or restarting. A missing store entry SHALL NOT cause an error when the incoming call carries full config. Old tiny records with only `jwt plus hostCommand plus mode` SHALL heal to full config on the next carrying call and SHALL be treated as no drift for missing fields until healed.

#### Scenario: Mismatch read repairs a wiped store
- **WHEN** the manager has no stored config and receives `get-mismatch{serverId, config}`
- **THEN** it persists the provided full config first and diffs the running process against it

#### Scenario: Host refreshes the store
- **WHEN** the manager receives `host-server` with full config
- **THEN** it persists the full config before restarting the process
