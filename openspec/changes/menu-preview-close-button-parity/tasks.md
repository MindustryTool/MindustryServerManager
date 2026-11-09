## 1. Preview Renderer Parity

- [x] 1.1 Add `dialog.addCloseButton()` call in `FrameBufferCapturer.capture(...)` before `UiTreeBuilder.build(...)`, matching `Menus.menuBuilder` ordering.
- [x] 1.2 Verify the change compiles (`:plugin:compileTestJava`).

## 2. Regenerate and Verify Previews

- [x] 2.1 Run `RenderAllMenuPreviewsTest` to regenerate all preview PNGs.
- [x] 2.2 Visually compare a regenerated preview (e.g., `welcome-desktop.png`) against the production screenshot to confirm the cancel button is present and proportions match.
- [x] 2.3 Run `MenuSnapshotTest` to ensure no regressions in snapshot path/viewport logic.