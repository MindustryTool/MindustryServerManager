## Context

Production server menus are rendered on the client by `Menus.menuBuilder`, which always calls `dialog.addCloseButton()` before building the content tree. The preview renderer in `FrameBufferCapturer.capture(...)` builds an equivalent `BaseDialog` but omits that call, causing preview images to lack the default 64 px cancel button and making dialog proportions differ from in-game dialogs.

## Goals / Non-Goals

**Goals:**
- Make preview dialogs structurally match production dialogs by adding the default close button.
- Keep preview rendering deterministic (scale pinned to 1.0, mobile viewports at desktop scale).

**Non-Goals:**
- Simulating device-specific mobile density scaling.
- Mirroring network-related guards (`net.active()`) or image streaming (`addImages`) in static captures.
- Changing production runtime code.

## Decisions

- **Add `dialog.addCloseButton()` before `UiTreeBuilder.build(...)`** in `FrameBufferCapturer.capture(...)`, matching the call order in `Menus.menuBuilder`.
- **Keep `BaseDialog`** rather than switching to `MenuDialog`: `addCloseButton()` is inherited from `Dialog`, so no subclass change is needed.
- **Keep `Scl.setProduct(1f)`** to avoid machine-dependent scaling.
- **Keep mobile viewports at desktop scale (1.0)** for deterministic, reproducible previews.

## Risks / Trade-offs

- [Risk] Existing generated preview PNGs will change (dialog now taller due to cancel button). → Mitigation: regenerate previews via `RenderAllMenuPreviewsTest`.
- [Risk] Image-heavy menus may still differ because server-streamed images are not reloaded in preview. → Mitigation: out of scope for this change; tracked separately if needed.

## Migration Plan

1. Edit `FrameBufferCapturer.capture(...)` to call `dialog.addCloseButton()`.
2. Regenerate all preview images via the renderer test suite.
3. Verify visually against production screenshots.

## Open Questions

(none)