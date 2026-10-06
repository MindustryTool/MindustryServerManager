# docker-build

## Purpose

Fast local-only image builds via `Dockerfile.local` with unchanged prod outputs. Prod `Dockerfile` stays canonical.

## Requirements

### Requirement: Local image build skips tests

`Dockerfile.local` SHALL produce jars without running Gradle `test` or `check` tasks. Prod `Dockerfile` stays unchanged.

#### Scenario: Local build uses assemble

- **WHEN** the `Dockerfile.local` build `RUN` executes
- **THEN** it invokes `assemble` targets for `server` and `plugin` with explicit test skips and does not invoke `build`

### Requirement: Local Gradle cache reuse across rebuilds

`Dockerfile.local` SHALL reuse Gradle dependency caches across rebuilds via a BuildKit cache mount.

#### Scenario: Rebuild hits cache mount

- **WHEN** a developer rebuilds with `Dockerfile.local` after a source edit with BuildKit enabled
- **THEN** dependency resolution does not re-download unchanged modules from scratch

### Requirement: Build context excludes local output and docs

The build context SHALL exclude local run output and non-source docs so edits outside code do not bust the build layer.

#### Scenario: Data edit does not bust build

- **WHEN** files under `data/` change and sources do not
- **THEN** the `COPY .` layer remains cached

#### Scenario: Doc edit does not bust build

- **WHEN** Markdown or planning docs change and sources do not
- **THEN** the `COPY .` layer remains cached

### Requirement: Local parallel builds

`Dockerfile.local` SHALL enable Gradle `--parallel` since its local trial passed and CI is untouched.

#### Scenario: Local parallel build passes

- **WHEN** the local image builds with `--parallel`
- **THEN** both jars plus sha are produced and the runtime starts

### Requirement: Local image outputs match prod layout

The local image SHALL still contain `application.jar`, `plugin.jar`, and matching `plugin.sha256`.

#### Scenario: Outputs present

- **WHEN** the local build stage finishes
- **THEN** `server/build/libs/application.jar` and `plugin/build/libs/plugin.jar` plus its sha exist and are copied to the runtime stage
