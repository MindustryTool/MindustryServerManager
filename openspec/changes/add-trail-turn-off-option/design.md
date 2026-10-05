## Context

`TrailMenu` is a paginated `PluginMenu<Integer>` displaying trails registered in `TrailService`. Currently, clicking the active trail toggles it off (`session.getData().trail = ""`), but this behavior is not discoverable. Players require an explicit "None" option to turn off active trails.

## Goals / Non-Goals

**Goals:**
- Provide an explicit, discoverable "None" option in `TrailMenu` to clear the player's active trail.
- Accurately reflect whether the player currently has no trail equipped via an active indicator (`●`).
- Provide immediate feedback to the player in chat upon turning off the trail.
- Ensure full internationalization across all supported languages.

**Non-Goals:**
- Changing trail rendering logic in `TrailService`.
- Altering the existing toggle behavior when clicking the current active trail directly.
- Redesigning pagination or other menu features.

## Decisions

### Decision 1: "None" option placement on Page 0
- **Choice**: Display the "None" option at the top of the trail list only on page 0 (`currentPage == 0`).
- **Rationale**: Page 0 is the initial page opened by `/trail`. Placing it at the top makes it immediately visible when opening the menu without taking up persistent slots on subsequent pages or crowding the bottom navigation controls (`Previous`, `Next`, `Close`).
- **Alternatives Considered**:
  - *Bottom navigation bar button*: Would clutter the navigation controls or take a separate row on every page.
  - *Pinned at top of every page*: Redundant across pages and reduces the available trail display height.

### Decision 2: Visual Indicator on "None"
- **Choice**: Display `[accent]● [green]<None>` when `session.getData().trail` is empty or null, and `[green]<None>` when any trail is active.
- **Rationale**: Consistent with the dot prefix `[accent]● [green]` used for active trail items in `TrailMenu`.

### Decision 3: Menu Dismissal & Feedback
- **Choice**: Clear trail, mark session dirty, send a chat message (`trail.removed`), and close the menu.
- **Rationale**: Follows the existing pattern in `TrailMenu` where selecting an option finishes the interaction and closes the menu.

## Risks / Trade-offs

- **[Players on page 1+ cannot see "None" directly]** → Page 0 is the default entry point. Players can quickly press `Previous` to return to page 0, or toggle their current trail off directly if it appears on their current page.
