## Why

Currently, players who open `/trail` can only equip trails or toggle an already active trail off by finding and clicking that exact active trail again. This mechanism is undiscoverable, unintuitive, and inconvenient—especially when navigating across multiple pages of trails. Introducing an explicit "None" option at the top of the trail list allows players to easily disable their active trail with clear feedback.

## What Changes

- Add a "None / No Trail" option at the top of the trail list on Page 0 in `TrailMenu`.
- Display an active indicator (`●`) on "None" when the player currently has no trail equipped, and plain text when a trail is active.
- When clicked, clear the active trail (`session.getData().trail = ""`), mark session dirty, send a confirmation chat message (`trail.removed`), and close the menu.
- Add localization strings (`trail.none`, `trail.removed`) across supported locale files (`i18n/*.json`).

## Capabilities

### New Capabilities
- `trail-selection`: Covers player trail selection, active trail state indication, and explicit trail removal/disable via the trail menu interface.

### Modified Capabilities
<!-- None -->

## Impact

- `plugin/src/main/java/plugin/trail/TrailMenu.java`: Adds the "None" entry on page 0 with state indication and action handler.
- `plugin/src/main/resources/i18n/*.json`: Adds `none` and `removed` keys under the `trail` namespace in all locale catalogs.
- No database schema or breaking API changes.
