## Why

The preview renderer (`FrameBufferCapturer`) builds the same `ServerMenu` UI as production but omits the default close/cancel button that `Menus.menuBuilder` always adds via `dialog.addCloseButton()`. This makes preview images structurally different from in-game dialogs: the preview lacks the 64 px cancel button, so button proportions and dialog height do not match what players see on the production server.

## What Changes

- Update `FrameBufferCapturer.capture(...)` to call `dialog.addCloseButton()` before `UiTreeBuilder.build(...)`, mirroring `Menus.menuBuilder`.
- Keep mobile viewports at desktop UI scale (1.0) for deterministic previews.
- Keep `Scl.setProduct(1f)` pinning to avoid machine-dependent scaling.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `server-menu-preview`: The offscreen rendering SHALL include the default close button so preview dialogs match production dialogs structurally.

## Impact

- Affected code: `plugin/src/test/java/plugin/menus/preview/FrameBufferCapturer.java`.
- No production runtime impact (test/dev scope only).
- Existing preview tests and generated PNGs will change (dialog now includes cancel button).