# server-jvm-container-args Specification

## Purpose
TBD - created by archiving change optimize-server-jvm-args. Update Purpose after archive.
## Requirements
### Requirement: JVM JIT Compiler Optimization
`DockerNodeManager` SHALL configure container `JAVA_TOOL_OPTIONS` to allow full HotSpot C2 JIT optimization while bounding native code cache allocation.

#### Scenario: C2 JIT enabled with bounded code cache
- **WHEN** `DockerNodeManager` constructs container environment variables for a server
- **THEN** `JAVA_TOOL_OPTIONS` MUST contain `-XX:ReservedCodeCacheSize=64m`
- **AND** `JAVA_TOOL_OPTIONS` MUST NOT contain `-XX:TieredStopAtLevel=1`

### Requirement: G1 Garbage Collector Configuration
`DockerNodeManager` SHALL configure container `JAVA_TOOL_OPTIONS` to use G1GC without artificial heap-free ratio clamping.

#### Scenario: G1GC enabled without excessive shrinking
- **WHEN** `DockerNodeManager` constructs container environment variables for a server
- **THEN** `JAVA_TOOL_OPTIONS` MUST contain `-XX:+UseG1GC`
- **AND** `JAVA_TOOL_OPTIONS` MUST NOT contain `-XX:+UseSerialGC`
- **AND** `JAVA_TOOL_OPTIONS` MUST NOT contain `-XX:MinHeapFreeRatio=` or `-XX:MaxHeapFreeRatio=`

### Requirement: Multi-core Thread Concurrency
`DockerNodeManager` SHALL allow the JVM to adapt its thread counts to the container cpuset without artificial single-core throttling.

#### Scenario: Active processor count unconstrained
- **WHEN** `DockerNodeManager` constructs container environment variables for a server
- **THEN** `JAVA_TOOL_OPTIONS` MUST NOT contain `-XX:ActiveProcessorCount=1`

### Requirement: Memory and Crash Guardrails
`DockerNodeManager` SHALL retain heap dump and out-of-memory crash protection parameters.

#### Scenario: Preserved memory limits
- **WHEN** `DockerNodeManager` constructs container environment variables for a server
- **THEN** `JAVA_TOOL_OPTIONS` MUST contain:
  - `-XX:+CrashOnOutOfMemoryError`
  - `-XX:+HeapDumpOnOutOfMemoryError`
  - `-XX:HeapDumpPath=/config`
  - `-XX:MaxDirectMemorySize=128m`
  - `-XX:-ShrinkHeapInSteps`
  - `-XX:MaxRAMPercentage=60`

