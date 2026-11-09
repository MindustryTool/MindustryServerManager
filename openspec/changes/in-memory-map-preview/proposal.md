## Why

`Utils.generateMapPreview()` writes the current game state to `map-preview.msav` on disk on the Mindustry main thread, then immediately reads it back on an IO thread to POST to the external image renderer. The temp file is never reused — it is a pure hand-off that causes disk writes, a backup-file rotation on every generation, and eventual orphaned files.

## What Changes

- Replace the `SaveIO.save(tempFile)` → `tempFile.readBytes()` round-trip in `plugin/utils/Utils.java` with an in-memory save: `SaveIO.write(OutputStream, SaveOptions)`.
- The save must remain byte-compatible with the current implementation: `new SaveOptions()` (default, `embedAssets=false`) wrapped in `FastDeflaterOutputStream` so the external `image-json` service still receives a deflated `.msav`.
- Keep main-thread confinement via `appPostWithTimeout` (3s timeout, now returning `byte[]` instead of writing a file) and keep the throttled `Tasks.io` POST identical.
- Keep `map-preview-image.png` on disk — `GatewayClient#getImage()` reads it via `nodeManager` after the `generate-map-image` RPC, whose contract stays `Void`.
- Do not touch `HostService` `LAST_MAP.msav` persistence (`SaveIO.save(SAVE_FILE)` on exit) — restart recovery still requires a real file.
- Do not add cleanup for files already on disk (per discussion: leave orphans). New generations simply stop creating `map-preview.msav` and its backup.

## Capabilities

### New Capabilities

- `live-map-preview-generation`: Live-map PNG generation from the current game state via the external image renderer, including the in-memory msav hand-off and the on-disk PNG hand-off to `get-image`.

### Modified Capabilities

_None — `map-preview-streaming` (pre-rendered RTV textures) is unaffected._

## Impact

- Affected code: `plugin/src/main/java/plugin/utils/Utils.java` only (`generateMapPreview`, constants `MAP_PREVIEW_FILE_NAME`).
- No RPC or API change: `generate-map-image` remains fire-and-forget `Void`; `GatewayClient#getImage()` still reads `map-preview-image.png` from the node volume.
- No dependency change: uses existing `mindustry.io.SaveIO#write(OutputStream, SaveOptions)` and `arc.util.io.FastDeflaterOutputStream` from the Mindustry jar.
- Risk: if the deflater wrapper is omitted the msav bytes are uncompressed and the remote renderer fails — the design note below guards this.
