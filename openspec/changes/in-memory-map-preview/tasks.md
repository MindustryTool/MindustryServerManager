## 1. In-memory msav generation

- [x] 1.1 Replace `SaveIO.save(tempFile)` + `tempFile.readBytes()` in `Utils.generateMapPreview` with `appPostWithTimeout` returning `byte[]` via `SaveIO.write(OutputStream, new SaveOptions())` wrapped in `ByteArrayOutputStream` + `FastDeflaterOutputStream`
- [x] 1.2 Keep `Tasks.io` image-json POST and `map-preview-image.png` file write unchanged; keep 3-minute `lastImagePreviewAt` throttle, 3s main-thread timeout, `synchronized` guard, and `bytes.length == 0` early return
- [x] 1.3 Remove `MAP_PREVIEW_FILE_NAME` constant and file-creation code; keep `MAP_PREVIEW_IMAGE_FILE_NAME` and `generate-map-image` RPC as `Void`
- [x] 1.4 Ensure `HostService` `LAST_MAP.msav` persistence and `GatewayClient#getImage()` volume read remain untouched; leave pre-existing orphan msav/backup files as-is

## 2. Verification

- [ ] 2.1 Compile plugin and run a host + `generate-map-image` smoke test confirming a valid PNG is written by the remote renderer (guards the deflater wrapper)
- [ ] 2.2 Confirm failure path: a forced save error propagates as `RuntimeException` (fail loud) and the prior PNG is still served by `get-image`
- [x] 2.3 Run `openspec validate in-memory-map-preview` and any related plugin tests
