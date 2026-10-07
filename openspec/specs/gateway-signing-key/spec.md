# gateway-signing-key

## Purpose

Defines the required `GATEWAY_SIGNING_KEY` environment value as the sole HMAC key for gateway JWT signing and verification, with fail-fast boot validation, constructor injection into `WsHandler`, silent logging, and hard-cut key rotation. Synced from change require-gateway-signing-key.

## Requirements

### Requirement: Required gateway signing key

The system SHALL use the `GATEWAY_SIGNING_KEY` environment value as the sole HMAC key for gateway JWT sign and verify. The system SHALL NOT generate a random key and SHALL NOT read any key file. `EnvConfig` SHALL require the value to be non-blank after trimming, and boot SHALL fail fast with a clear error when it is missing or blank. `ServerMain` SHALL pass the key into `WsHandler` via constructor. Boot logging SHALL NOT include the key value.

#### Scenario: Key used for mint and verify

- **WHEN** the manager mints a gateway JWT and a game server presents it
- **THEN** both operations use the `GATEWAY_SIGNING_KEY` value and tokens stay valid across manager restarts

#### Scenario: Missing key fails boot

- **WHEN** `GATEWAY_SIGNING_KEY` is unset or blank at boot
- **THEN** boot fails fast before serving traffic with an error naming the variable and no fallback key is used

#### Scenario: Key value stays secret in logs

- **WHEN** boot succeeds with a valid key
- **THEN** logs confirm the key loaded without printing the value, its length, or any hash of it

### Requirement: Hard-cut key rotation

The system SHALL rotate keys by changing `GATEWAY_SIGNING_KEY` and restarting. Old tokens SHALL fail verification once per server, `WsHandler.parseServerJwt()` SHALL mint a fresh JWT into `server.json` preserving `startServer`, and the client SHALL heal on redial by re-reading the file. The system SHALL NOT accept old and new keys at the same time.

#### Scenario: Rotation heals via server.json

- **WHEN** the env key changes and the manager restarts
- **THEN** each server fails one dial, its `server.json` JWT is rewritten, and the next dial succeeds with no manual step
