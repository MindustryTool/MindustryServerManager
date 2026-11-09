## Why

Menu dialogs hand-roll cancel/back buttons with ad-hoc sizing — heights like 36f and 38f are scattered across `WelcomeMenu`, `ShopMenu`, `GriefMenu`, Catali menus, and others. This creates inconsistency risk and duplicated layout logic.

The offscreen preview renderer previously had to omit Mindustry's default close button, which pushed teams toward custom buttons. A single reusable helper removes the guesswork and standardizes sizing.

## What Changes

- Add a reusable cancel button builder to `MenuContext` (e.g., `ctx.cancelButton(...)`) producing a full-width, 36 px tall button with standard padding.
- Migrate key menus to use the helper instead of ad-hoc `UiBuilder.button(...)` calls.

## Capabilities

### New Capabilities

- `menu-cancel-button`: Reusable cancel/back button support for server menus.

### Modified Capabilities

(none)

## Impact

- Affected code: `MenuContext`, menus (`WelcomeMenu`, `ShopMenu`, `GriefMenu`, Catali menus, etc.).
- Affected tests: preview snapshot tests, menu render tests.
