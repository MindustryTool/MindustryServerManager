## ADDED Requirements

### Requirement: Bounded session cache eviction

`SessionRepository` SHALL use a bounded, evicting cache for `SessionData` entries instead of an unbounded `ConcurrentHashMap`. Entries that exceed the configured TTL or maximum size SHALL be evicted. Dirty entries SHALL be flushed to the database before eviction.

#### Scenario: Cache has maximum size limit

- **WHEN** the number of cached `SessionData` entries reaches `maximumSize`
- **THEN** the least recently written entry is evicted to make room for new entries

#### Scenario: Expired entries are evicted

- **WHEN** a cached `SessionData` entry has not been written for longer than the configured TTL
- **THEN** the entry is evicted from the cache

#### Scenario: Dirty entry is flushed before eviction

- **WHEN** a `SessionData` entry in the `dirty` set is evicted from the cache
- **THEN** the entry is written to the database asynchronously before removal

#### Scenario: Active sessions are not evicted

- **WHEN** a player is online and their session is referenced by `SessionService.data`
- **THEN** their `SessionData` remains in the repository cache and is not subject to eviction

#### Scenario: Eviction does not lose session data

- **WHEN** an evicted `SessionData` entry has unsaved changes (dirty flag set)
- **THEN** the changes are persisted to the `sessions` database table before the entry is removed from cache
