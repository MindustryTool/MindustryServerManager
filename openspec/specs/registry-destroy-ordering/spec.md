# registry-destroy-ordering

## Requirements

### Requirement: Destroy runs in reverse DI order
`Registry.destroy()` SHALL invoke `@Destroy` methods in reverse creation order: the most recently created component is destroyed first, and a component is destroyed only after every component that depends on it (directly or transitively, via constructor injection or lazy `get()` acquisition) has been destroyed.

#### Scenario: Dependents destroyed before their dependencies
- **WHEN** components A and B are registered where A's constructor injects B, and both declare `@Destroy` methods
- **THEN** `Registry.destroy()` invokes A's `@Destroy` before B's `@Destroy`

#### Scenario: Transitive dependencies destroyed last
- **WHEN** A injects B and B injects C, and all three declare `@Destroy` methods
- **THEN** destroy order is A, then B, then C

#### Scenario: Lazily created components are destroyed before their acquirers
- **WHEN** component X obtains lazy component Y at runtime via `Registry.get(Y.class)` after `init()` completed, and both declare `@Destroy` methods
- **THEN** `Registry.destroy()` invokes Y's `@Destroy` before X's `@Destroy`

#### Scenario: Sibling components destroyed in creation order
- **WHEN** two independent components have no dependency relationship
- **THEN** their `@Destroy` methods run in creation order relative to each other (earlier-created destroyed later)

### Requirement: Deterministic destroy order
The `@Destroy` invocation order SHALL be fully deterministic across repeated runs and SHALL NOT depend on hash-based map iteration order.

#### Scenario: Order stable across runs
- **WHEN** `Registry.destroy()` is called multiple times across plugin reloads with the same component registration set
- **THEN** the `@Destroy` invocation order is identical each time

### Requirement: Destroy semantics preserved
`Registry.destroy()` SHALL preserve the existing teardown contract: each `@Destroy` method on each registered component runs exactly once, a failure in one `@Destroy` method is logged and does not abort the remaining invocations, and after destroy the registry's instance and initialization state is cleared.

#### Scenario: Once-only invocation
- **WHEN** a component declares multiple `@Destroy` methods or is obtained through multiple paths
- **THEN** each `@Destroy` method is invoked exactly once during a single `destroy()` call

#### Scenario: Failure does not abort remaining destroys
- **WHEN** one component's `@Destroy` method throws
- **THEN** the error is logged and `@Destroy` methods of all remaining components still run

#### Scenario: Registry cleared after destroy
- **WHEN** `destroy()` completes
- **THEN** subsequent `getOrNull` calls return `null` and subsequent `get` calls create fresh instances as if the registry were new

### Requirement: Public API unchanged
The destroy-ordering change SHALL NOT alter the signatures or semantics of `Registry.init`, `get`, `getOrNull`, `getAll`, `createNew`, or `inject`, and SHALL NOT require changes to any component's annotations or constructor signatures.

#### Scenario: Existing API surface intact
- **WHEN** the plugin is compiled after the change
- **THEN** all existing call sites of `Registry` public methods compile unchanged and behave as before apart from destroy ordering
