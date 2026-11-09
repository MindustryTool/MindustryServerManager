## Context

`plugin/utils/Utils.java#generateMapPreview(boolean)` currently does:

1. `appPostWithTimeout(() -> SaveIO.save(tempFile), 3000, ...)` on the Mindustry main thread → writes `Vars.dataDirectory/map-preview.msav` to disk (with backup rotation).
2. `Tasks.io("generate map preview", ...)` → `tempFile.readBytes()`, base64, POST to `https://api.mindustry-tool.com/api/v4/maps/image-json`, on success `tempImageFile.write(resStream, false)` → `map-preview-image.png`.

The msav is a throwaway. `SaveIO.save(Fi)` delegates to `SaveIO.write(OutputStream, SaveOptions)` after wrapping the file stream in `FastDeflaterOutputStream`. `SaveIO.write(OutputStream, SaveOptions)` is public and does not touch the filesystem or create backups.

Server-side, `GatewayClient#getImage()` triggers `generate-map-image` RPC then reads the PNG from the node volume. That contract stays.

## Goals / Non-Goals

**Goals:**
- Eliminate the disk write/read for the live-map msav hand-off.
- Preserve exact wire bytes the external renderer expects.
- Keep thread model and throttling identical; keep PNG on disk.
- Fail loud on save errors (consistent with project rules and current behavior).

**Non-Goals:**
- No RPC change (`generate-map-image` stays `Void`).
- No PNG in-memory delivery or cache.
- No cleanup of pre-existing `map-preview.msav` / backup orphans (leave as-is per decision).
- No change to `HostService` `LAST_MAP.msav` persistence.

## Decisions

### D1: Use `SaveIO.write(OutputStream, SaveOptions)` with a deflater wrapper

Rationale: `SaveIO.write(Fi, ...)` is literally `new FastDeflaterOutputStream(Fi.write()) → write(OutputStream, ...)`. Reusing the stream overload keeps bytes identical while skipping file IO and backup rotation.

Concretely, inside `appPostWithTimeout` return `byte[]`:
- `ByteArrayOutputStream raw = new ByteArrayOutputStream()`
- `try (FastDeflaterOutputStream deflated = new FastDeflaterOutputStream(raw)) { SaveIO.write(deflated, new SaveOptions()); }` then `raw.toByteArray()`
- Alternative considered: raw `ByteArrayOutputStream` directly → rejected. The remote renderer inflates (`SaveIO.getStream` uses `InflaterInputStream`); uncompressed bytes would fail to parse.
- Alternative considered: `SaveIO.getSaveWriter().write(DataOutputStream, ...)` → rejected: one more layer than the public `write(OutputStream, ...)` entry point and duplicates header/version handling.

`SaveOptions` is the implicit default (`new SaveOptions()`, `embedAssets=false`), matching `SaveIO.save(Fi)`. Any `embedAssets` or `extraTags` change would alter external rendering and is out of scope.

### D2: Return `byte[]` from the main-thread lambda, keep `Tasks.io` POST unchanged

Rationale: `appPostWithTimeout` already has a `Supplier<T>` overload returning `T`. Switch from `Runnable` (side-effect file write) to `Supplier<byte[]>` so the IO task consumes the bytes directly. The `Tasks.io` body (base64 → `image-json` POST → write PNG) stays verbatim except it now uses the captured `bytes` instead of `tempFile.readBytes()`.

The `bytes.length == 0` early-return guard stays.

### D3: Keep throttle, timeout, synchronization

- `synchronized generateMapPreview` and the 3-minute `lastImagePreviewAt` throttle stay — they protect the external API (180s HTTP timeout), not disk.
- `appPostWithTimeout(..., 3000, ...)` stays; a timeout throws `RuntimeException`, which is the desired fail-loud behavior.

### D4: Keep PNG on disk, leave orphans

Rationale: `GatewayClient#getImage()` reads the volume file after the RPC. Returning PNG bytes via `StreamReply` would remove the last file but changes the RPC contract across plugin + server — explicitly deferred. Orphaned `map-preview.msav` files are left untouched; new runs just stop creating them.

## Risks / Trade-offs

- [Risk] Forgetting the `FastDeflaterOutputStream` wrapper → uncompressed bytes → remote `image-json` fails. → Mitigation: use the wrapper; verify with a real render test before release, and note in code via the wrapper's presence.
- [Risk] `ByteArrayOutputStream` memory pressure on very large maps → Mitigation: current code already holds the full `bytes` + base64 copy in memory after `readBytes()`; this change removes the disk round-trip but does not increase peak heap (one transient copy instead of two).
- [Risk] Exception from `SaveIO.write` now surfaces from `Supplier` as `ExecutionException` → wrapped `RuntimeException` by `appPostWithTimeout` → Mitigation: desired fail-loud; stale PNG remains on disk and `getImage()` still serves it.
- [Trade-off] Orphaned msav/backup files remain on existing installs. Accepted per explicit decision; no migration code.

## Migration Plan

Single deploy, no migration: existing volumes keep their old files, new code simply never writes `map-preview.msav` again. Rollback is reverting `Utils.java` (old file-based path returns; PNG remains valid).

## Open Questions

None — scope (msav-only), orphans (leave), format (default `SaveOptions`), and failure mode (fail loud) were decided in explore mode.
