## ADDED Requirements

### Requirement: Functions are registry metadata, not node types
The system SHALL represent every callable Mindustry/plugin operation as a `FunctionDescriptor` record executed through the single generic Call node type, and SHALL NOT create dedicated node classes per function.

#### Scenario: Two different functions share one node type
- **WHEN** a graph contains a Call node bound to `mindustry.player.sendMessage` and another bound to `mindustry.world.tile`
- **THEN** both nodes are instances of the same generic `call` node type differing only in their `function` field and metadata-derived ports

### Requirement: Complete function descriptor
Each registered function SHALL have a stable id, display name, description, category, owner type, ordered parameters with types and nullability, return type with nullability, overload list with stable overload hashes, generic templates, throws flag, thread requirement (`MAIN_THREAD|ASYNC|PURE|READ_ONLY|UNSAFE`), codegen-safe flag, aliases, deprecation info, since-version, and an advisory `advanced` display flag; descriptors SHALL NOT carry security-enforcement fields.

#### Scenario: Descriptor exposes execution requirements
- **WHEN** the frontend fetches the descriptor for an HTTP function
- **THEN** the descriptor reports thread requirement `ASYNC` and the function is scheduled off-main-thread by generated code

#### Scenario: Advanced functions are marked for display only
- **WHEN** a raw packet helper is registered with `advanced=true`
- **THEN** the metadata carries the flag for editor grouping/warning purposes without any runtime denial behavior

### Requirement: Reflection-based registration channels
The system SHALL populate the registry by reflecting over configured class roots (Mindustry, Arc, plugin packages), deriving descriptors from public methods, constructors, fields, and event types, with cached reflective invokers bound once per overload; hand-written wrapper/facade classes SHALL NOT be required, and adding a new Java function SHALL NOT require any new frontend node type. Programmatic registration remains available for plugin contributions and metadata overrides.

#### Scenario: Plugin contributes a custom function
- **WHEN** a plugin module registers a descriptor and cached invoker for `myplugin.mute.player`
- **THEN** the function becomes discoverable through the search API and invocable from a standard Call node without editor or backend node changes

#### Scenario: Mindustry methods discoverable without wrappers
- **WHEN** the registry loads its reflection index for root `mindustry.gen`
- **THEN** methods such as Player.sendMessage appear as searchable descriptors with correct parameter and return types, with no hand-written wrapper involved

### Requirement: Build-time index replaced by lazy reflective scan
The registry index SHALL be produced by a **lazy** reflection pass over configured roots on first query or first compilation use; the plugin SHALL NOT scan classes eagerly at startup, and SHALL NOT require a build-time annotation-processing step to expose Mindustry APIs (annotation enrichment stays optional).

#### Scenario: Startup does not scan the classpath
- **WHEN** the plugin initializes with the graph subsystem enabled and no graph operation occurs
- **THEN** no reflective traversal of Mindustry API packages happens during startup

### Requirement: Lazy metadata loading
Full descriptors SHALL be loaded lazily: startup loads only a compact id/category/type-summary index, and full pages load on first query or first use by a compiling graph.

#### Scenario: Metadata fetched on demand
- **WHEN** no graph operation has queried functions since boot
- **THEN** no full descriptor pages are materialized until the first search or compilation request

### Requirement: Function discovery API
The backend SHALL expose a paged, fuzzy-searchable discovery API (hosted exclusively in the server module) supporting filters by category, owner type, aliases, and compatibility with a given target type, returning descriptors with a registry fingerprint for client caching.

#### Scenario: Type-aware search
- **WHEN** the client requests functions with `compatibleWith=Player`
- **THEN** results include only functions accepting or owned by `Player` (e.g., Send Message, Kick, Get Team) grouped with category metadata

#### Scenario: Cached registry with fingerprint
- **WHEN** the client sends a conditional request with an unchanged registry fingerprint
- **THEN** the server responds not-modified and the client reuses its cached page

### Requirement: Property and constructor descriptors
Properties (get/set) and constructors SHALL be described as registry metadata analogous to functions, consumed by the generic Get Property, Set Property, and Construct nodes.

#### Scenario: Property rendered as ports
- **WHEN** a Get Property node selects property `team` on `Player`
- **THEN** its ports are derived from the property descriptor (input: Player, output: Team)

### Requirement: Registry change invalidation
Any registration, removal, or signature change SHALL bump the registry fingerprint and invalidate compile-cache entries only for graphs whose used-function set intersects the change.

#### Scenario: Unrelated graphs survive a registry update
- **WHEN** a new overload is added for function F and graph G does not use F while graph H does
- **THEN** G's compiled artifact remains valid and H's cache entry is invalidated for revalidation
