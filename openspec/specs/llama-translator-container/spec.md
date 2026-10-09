# llama-translator-container Specification

## Purpose
Provides a lightweight, self-contained translation engine running `llama-server` in Docker with automated model caching and GitHub Actions CI publishing.

## Requirements

### Requirement: Llama Translator Container
The system SHALL provide a Dockerized translation sidecar service running `llama-server`. On startup, the container SHALL check for the configured GGUF model in the `/models` directory and automatically download it if missing before launching `llama-server`. The server SHALL be configured with context length capped at 256 tokens and 1 CPU thread.

#### Scenario: First run downloads model to volume
- **WHEN** the container starts and the model file is not present in `/models`
- **THEN** the entrypoint script downloads the model file from Hugging Face before starting `llama-server`

#### Scenario: Subsequent runs reuse cached model
- **WHEN** the container starts and the model file already exists in `/models`
- **THEN** the entrypoint script skips download and starts `llama-server` immediately

#### Scenario: Server operates within minimal memory bounds
- **WHEN** `llama-server` launches
- **THEN** it runs with `-c 256`, `-t 1`, and `--slots 1` listening on port 8080

### Requirement: Translator CI Workflow
The project SHALL provide a GitHub Actions workflow `.github/workflows/build-translator.yaml` that triggers on commits to `main` touching `translator/**` and builds and pushes the image to `ghcr.io/mindustrytool/llama-translator:latest`.

#### Scenario: Workflow builds on translator changes
- **WHEN** changes are pushed to `main` affecting `translator/**`
- **THEN** GitHub Actions builds the Docker image and publishes it to GHCR
