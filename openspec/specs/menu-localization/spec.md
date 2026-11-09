# menu-localization

## Purpose

Localization requirements for server menu user-facing text.

## Requirements

### Requirement: Menu user-facing text is localized
All user-facing text displayed by server menus SHALL be resolved through `Tr.t` using semantic translation keys. Hardcoded English literals in menu titles, labels, buttons, empty states, badges, and menu-triggered feedback messages SHALL NOT be used for language-dependent text.

#### Scenario: Menu displays a localized title
- **WHEN** a server menu is built for a player
- **THEN** its title is produced by `Tr.t` or a documented non-translatable brand/icon value

#### Scenario: Menu displays a localized button
- **WHEN** a server menu renders an action button
- **THEN** the button text is produced by `Tr.t` rather than a hardcoded English literal

#### Scenario: Menu shows an empty state
- **WHEN** a list or pane has no items
- **THEN** the empty-state message is produced by `Tr.t`

#### Scenario: Menu triggers feedback
- **WHEN** a menu action sends a message to a player
- **THEN** that message is produced by `Tr.t`

### Requirement: Non-English menus use genuine translations
For every locale supported by the plugin, menu text SHALL render in that locale's language when a translation exists. Locale catalogs SHALL NOT rely on English copies solely to satisfy key parity.

#### Scenario: Vietnamese player opens a menu
- **WHEN** a player with Vietnamese locale opens any server menu
- **THEN** the menu's user-facing text is Vietnamese where the catalog provides a translation

#### Scenario: Parity is not enough
- **WHEN** a new menu key is added to `en.json`
- **THEN** each non-English catalog receives a real translation or an explicitly tracked follow-up, not an English copy by default