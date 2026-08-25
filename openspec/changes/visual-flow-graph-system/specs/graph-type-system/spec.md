## ADDED Requirements

### Requirement: Supported type universe
The type system SHALL support primitives (`String, Int, Long, Float, Double, Boolean, Byte`), registered Mindustry types (`Player, Unit, Building, Tile, Team, Block, Item, Liquid, Bullet, World, GameState, Connection`, extensible), generics (`List<T>, Set<T>, Map<K,V>`), wrappers (`Optional<T>, Future<T>`), and explicit nullability on every slot.

#### Scenario: Generic collection typing
- **WHEN** a ForEach node iterates an expression typed `List<Player>`
- **THEN** the loop variable binds as non-nullable `Player`

### Requirement: Connection compatibility checking
An edge between two ports SHALL be accepted only if the source type is assignable to the target type under the implicit conversion matrix (numeric widening, literal-friendly String/primitive conversions); all other connections SHALL be rejected with a node-and-port-specific error.

#### Scenario: Invalid connection rejected
- **WHEN** a user attempts to connect a `Tile` output to a `String` input with no applicable conversion
- **THEN** the connection is rejected at edit time pre-check and, if present in a document anyway, fails compilation with a diagnostic naming both ports

#### Scenario: Widening conversion allowed
- **WHEN** an `Int` output connects to a `Float` input
- **THEN** the edge is accepted and the compiler inserts the widening conversion

### Requirement: Explicit casts and conversions
The Cast/Convert node SHALL perform explicit downcasts, numeric narrowing, and parse/format conversions registered in the conversion registry; failed casts SHALL produce catchable runtime errors rather than JVM `ClassCastException` leaks outside error handling.

#### Scenario: Failed parse is catchable
- **WHEN** a Convert node parses `"abc"` to Int inside a Try block
- **THEN** the Catch branch receives a typed conversion error instead of crashing the server thread

### Requirement: Generic type inference
The type checker SHALL infer generic parameters by constraint unification across connected nodes within an execution path, reporting unresolved-inference errors when constraints are contradictory.

#### Scenario: Inference across nodes
- **WHEN** a Map-entry getter feeds a ForEach that feeds Get Property `name`
- **THEN** inference resolves element types end-to-end without manual annotations, or emits a precise inference-failure diagnostic

### Requirement: Nullability enforcement
Passing a nullable-typed value into a non-nullable input SHALL be rejected unless the graph includes an explicit null check/default construct; generated code for validated paths SHALL NOT require defensive reflection-style null probing beyond declared checks.

#### Scenario: Nullable source blocked
- **WHEN** an `Optional<Player>` unwraps into a non-null Player input without a presence check
- **THEN** validation fails identifying the offending edge and suggesting a null-check node

### Requirement: Authoritative backend validation
Frontend type checks are advisory; the compiler's type checker SHALL be authoritative and SHALL reject documents containing invalid edges, missing inputs, or incompatible casts before code generation.

#### Scenario: Tampered document rejected
- **WHEN** a hand-edited graph JSON contains a type-invalid edge bypassing the editor
- **THEN** validate/compile returns structured diagnostics referencing node id, port, expected, and actual types
