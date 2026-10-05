## 1. Localization

- [ ] 1.1 Add `trail.none` and `trail.removed` translation keys to `en.json` and `vi.json`
- [ ] 1.2 Add `trail.none` and `trail.removed` translation keys to remaining locale files (`ar.json`, `es.json`, `fr.json`, `id.json`, `ja.json`, `ko.json`, `pl.json`, `ru.json`, `th.json`, `uk.json`, `zh.json`)

## 2. Trail Menu Implementation

- [ ] 2.1 Update `TrailMenu.java` to display the localized "None" option at the top of the trail list when on Page 0
- [ ] 2.2 Add active status indicator (`[accent]● [green]` vs `[green]`) for the "None" option based on whether a trail is active
- [ ] 2.3 Implement selection callback on the "None" option to clear `session.getData().trail`, mark session dirty in `SessionRepository`, send chat message `trail.removed`, and close the menu

## 3. Verification

- [ ] 3.1 Run build to ensure plugin compiles cleanly and catalog files are valid
- [ ] 3.2 Verify `TrailMenu` behavior and requirements against `trail-selection` spec
