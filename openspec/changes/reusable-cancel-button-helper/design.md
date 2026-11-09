## Context

Menus use `MenuContext.button(...)` or `UiBuilder.button(...)` directly, with ad-hoc sizing (`height(36f)`, `height(38f)`, `width(...)`, `align(...)`) per menu. The previous change made the preview renderer omit Mindustry's default close button, which pushed custom cancel buttons into menu content. Without a shared helper, every menu will re-implement the same pattern.

## Goals / Non-Goals

**Goals:**
- Provide a reusable, default-styled cancel/back button builder in the menu context.
- Standardize cancel button height at 36 px with full width and standard padding.

**Non-Goals:**
- Changing Mindustry's built-in close button behavior (client-side, not modifiable from server plugin).
- Migrating every menu in one change; helper first, adoption across menus later.

## Decisions

- Add `cancelButton(String text, MenuAction<T> action)` to `MenuContext`, delegating to existing `button(...)` with defaults `.growX().height(36f).pad(3f)`.
- Reuse `registerAction` internally via the existing `button(text, action)` overload.

## Risks / Trade-offs

- [Risk] Menus that used custom widths/heights (e.g., `width(120f)`, `height(38f)`) will need override paths. → Mitigation: keep helper defaults; allow per-menu override.

## Migration Plan

1. Add helper to `MenuContext`.
2. Update menus to use it.
3. Regenerate previews.

## Open Questions

(none)
