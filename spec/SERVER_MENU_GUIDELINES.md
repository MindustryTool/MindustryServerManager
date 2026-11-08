# Server Menu Design Guidelines

Guidelines for building server menus using Mindustry build 160's `MenuBuilder`, `UiBuilder`, and `MenuContext<T>`.

> [!IMPORTANT]
> **Java Builder API Only**: All menus in this project MUST be constructed using the Java `UiBuilder` fluent API. **Do not use the string/file DSL (`.msui`)**. The Java API provides compile-time safety, seamless data binding, loops, and direct action callbacks.

---

## 1. Core Principles

* **Usability First**: Design for fast gameplay, not screenshots. Eliminate decorative clutter.
* **Clear Purpose**: Every button, label, and container must serve a concrete player goal.
* **Mindustry Feel**: Follow native UI conventions. Avoid visual noise, custom frames, and heavy borders.
* **Fast Navigation**: Keep click counts low. Place high-frequency actions within easy reach.

---

## 2. Visual Hierarchy

1. **Primary Actions & Main Content**: Most visible, distinct, and prominent.
2. **Context & Status**: Clear supporting indicators (badges, counts, active states).
3. **Secondary Actions**: Neutral styling with smaller footprint.
4. **Decorative Elements**: Minimal and unobtrusive.

* **Separation**: Use whitespace and layout padding instead of heavy borders.
* **Predictability**: Place common controls (search, pagination, filters) in identical spots across screens.

---

## 3. Layout, Alignment & Sizing

* **Card Width Bounds in Scroll Panes**: Items inside scrollable panes (`ctx.pane(...)`) MUST specify `card.width(MenuContext.SAFE_CARD_WIDTH)` (`290f`). This provides Arc's `Table` with a concrete boundary to wrap dynamic and multi-column labels within the 320px scroll viewport. Without this bound, unconstrained labels expand the table past 320px, causing the scrollpane scissor bounds to clip buttons, counts, and arrows.
* **Left Alignment**: Mindustry centers elements by default. Panes and cards must be aligned to the left (`.align("left")`), with standard `padLeft(4f)` on inner labels matching `ctx.label(...)` for clean vertical alignment.
* **Rows and Columns**: Organize layouts in structured rows and columns using `.row()`.
* **Fill Available Width**: Use `growX` on cells inside bounded cards to fill card width cleanly.
* **Uniform Button Height**: Native dialogs feature a fixed cancel button. Match button heights to standard row heights (34–40px) for visual balance.
* **Button and Table Margins**: When styling tables or buttons with border drawables (e.g., `flatBordert` which has ~6px 9-patch border insets), use `.margin(12f)` to provide breathing room inside the border so text and content do not touch the border lines.
* **Mobile Viewport Boundaries**:
  * Root containers must stay within mobile safe width: `MenuContext.SAFE_WIDTH` (`320f`).
  * Card items inside scroll panes must fit: `MenuContext.SAFE_CARD_WIDTH` (`290f`).
  * Scrollable panes must stay within mobile max height: `MenuContext.MOBILE_MAX_PANE_HEIGHT` (`220f`).

---

## 4. Colors & Semantic Theming

Never communicate state through color alone. Always pair color with text or icons.

* **Green (`[green]`)**: Success, active state, completed task, available item.
* **Red (`[scarlet]`)**: Errors, destructive actions, unavailable items, cancel vote.
* **Yellow / Orange (`[accent]`)**: Highlights, selected tabs, warnings, pending state.
* **Blue (`[cyan]` / `[royal]`)**: Informational text, links, interactive utility.
* **Gray (`[gray]` / `[lightgray]`)**: Disabled items, secondary stats, timestamps.
* **Accent Restraint**: Use bright accent colors only for key focal points. Avoid glowing effects and arbitrary gradients.

---

## 5. Typography

* **Scale & Restraint**: Keep text readable across different screen resolutions. Avoid oversized headers.
* **Selective Bold**: Use bold text only for primary headers and card titles.
* **Alignment Rules**:
  * **Left**: Titles, descriptions, and list items.
  * **Center**: Short standalone messages or status alerts.
  * **Right**: Numbers, resource counts, prices, and vote tallies.
* **Text Wrapping**: Always call `.wrap()` on labels with dynamic or localized text to prevent off-screen clipping.

---

## 6. Components & State Handling

* **Reuse Native Patterns**: Share uniform button styling, corner radius, and padding across identical component tiers.
* **No Manual Close Buttons**: Mindustry dialogs provide a built-in cancel/close button. Never render duplicate close buttons.
* **Flat Layout Hierarchy**: Avoid deep table nesting and unnecessary wrappers.
* **Consistent Icons**: Use `Iconc` glyphs or icon regions with uniform visual weight.

### Required State Coverage
Every stateful or asynchronous menu must account for all 7 states:
1. **Initial**: Clean default idle view.
2. **Loading**: Clear progress or indicator while data fetches.
3. **Success**: Immediate feedback on action completion.
4. **Error**: Clear error message with a retry option.
5. **Empty**: Informative placeholder when lists have no items.
6. **Disabled**: Visually dimmed button when prerequisites are unmet.
7. **Unavailable**: Explanatory message when a feature is locked or offline.

---

## 7. UiBuilder Elements Reference (Java API)

Mindustry build 160 exposes `UiBuilder` elements constructed via static factory methods from `mindustry.ui.builder.UiBuilder.*`:

| Element | Java Factory Method | Purpose | Key Builder Methods |
| :--- | :--- | :--- | :--- |
| `table` | `table()` | Nested layout container | `.background(name)`, `.margin(pad)`, `.wrap()` |
| `pane` | `pane()` | Scrollable container wrapping an inner table | `.add(contentTable)`, `.style(name)` |
| `stack` | `stack()` | Layers children on top of each other in the same cell | `.add(child1).add(child2)` |
| `label` | `label(text)` | Text display | `.wrap()`, `.labelAlign(align)`, `.style(name)` |
| `image` | `image()`, `image(region)` | Sprite from texture atlas or icon | `.region(name)`, `.icon(name)`, `.scaling(mode)`, `.size(f)` |
| `button` | `button(text)` | Standard text button | `.icon(name)`, `.style(name)`, `.clicked(actionId)`, `.checked(bool)`, `.disabled(bool)`, `.group(name)` |
| `imageButton`| `imageButton(icon)` | Icon-only button | `.icon(name)`, `.style(name)`, `.clicked(actionId)`, `.checked(bool)`, `.disabled(bool)`, `.group(name)` |
| `buttonTable`| `buttonTable()` | Whole clickable container table acting as a button | `.clicked(actionId)`, `.style(name)`, `.margin(pad)`, `.disabled(bool)`, `.add(child)` |
| `field` | `field(text)` | Text input field | `.hint(hint)`, `.enter(actionId)`, `.maxLength(len)`, `.id(id)`, `.disabled(bool)` |
| `check` | `check()`, `check(text)` | Toggle checkbox | `.checked(bool)`, `.group(name)`, `.id(id)`, `.disabled(bool)` |
| `slider` | `slider(min, max, step)` | Numeric value slider | `.defaultValue(val)`, `.text(format)`, `.id(id)`, `.disabled(bool)` |
| `space` | `space()` | Empty spacing cell | Cell size/pad methods |
| `defaults` | `defaults()` | Sets default cell properties for subsequent siblings | Cell properties chain (`.pad(...)`, `.growX()`) |
| `row` | `table.row()` | Ends current row and begins a new row | Void |

---

## 8. Cell Properties Reference

Cell properties control sizing, expansion, and placement within parent tables:

### Expansion & Sizing
* `grow()`: Expand and fill both horizontally and vertically.
* `growX()` / `growY()`: Expand and fill horizontally or vertically.
* `fill()` / `fillX()` / `fillY()`: Fill the cell without requesting extra layout space.
* `expand()` / `expandX()` / `expandY()`: Allocate extra available table space to this cell.
* `width(float)` / `height(float)` / `size(float)`: Assign fixed cell dimensions.
* `minWidth(float)` / `maxWidth(float)` / `minHeight(float)` / `maxHeight(float)`: Boundary clamps.
* `uniform()` / `uniformX()` / `uniformY()`: Force cell size to match other uniform cells.

### Spacing & Alignment
* `pad(float)`: Outer cell padding on all four sides.
* `padTop(float)` / `padBottom(float)` / `padLeft(float)` / `padRight(float)`: Directional cell padding.
* `align(String)`: Alignment inside cell (`"left"`, `"right"`, `"center"`, `"top"`, `"bottom"`, `"topLeft"`, `"topRight"`, `"botLeft"`, `"botRight"`).
* `colspan(int)`: Span cell across multiple columns.
* `color(String)`: Tint cell color (hex code or standard color name).
* `disabled(boolean)`: Initial disabled state on disableable controls (`button`, `imageButton`, `field`, `check`, `slider`, `buttonTable`).

---

## 9. Built-in Style Reference

Pass style names to `.style("name")` on buttons and image buttons:

### Text Button Styles (`button` & `buttonTable`)
| Style | Visual Appearance | Common Usage |
| :--- | :--- | :--- |
| `defaultt` | Default Mindustry button, beveled 45-degree gray corners | Primary dialog actions |
| `flatt` | Flat, square, opaque dark background | Clean secondary controls |
| `grayt` | Flat, square, opaque gray background | Standard list items & selections |
| `flatBordert`| Flat, square with a visible gray border | Action outlines, card borders |
| `nonet` | Completely borderless and transparent, text only | Breadcrumbs, link buttons |
| `cleart` | Partially transparent square button | Overlaid controls, floating items |
| `flatTogglet`| Flat, square, toggle-capable button | Active tab selection |
| `clearTogglet`| Clear square button with orange highlight when toggled | Filter switches |

### Image Button Styles (`imageButton`)
| Style | Visual Appearance | Common Usage |
| :--- | :--- | :--- |
| `defaulti` | Default image button, beveled corners | Standard icon triggers |
| `flati` | Flat, square, black background | Compact toolbars |
| `cleari` | Partially transparent dark square | Overlay icon actions |
| `emptyi` | Borderless, tints icon directly when hovered | Inline card actions (delete, edit) |
| `emptyTogglei`| Borderless toggle button | Star/favorite toggles |
| `selecti` | Shows selection border around icon when active | Map & unit selector grids |
| `squarei` | Square border around icon | Grid item cells |

---

## 10. Implementation Patterns

### Pattern A: Standard Card List with In-Place Actions
```java
@Override
public void build(MenuContext<Integer> ctx) {
    this.title = Tr.t(ctx.getSession(), "rtv.available_maps");

    // Header label: aligned left, wrapped, accent color
    ctx.label("[accent]" + Tr.t(ctx.getSession(), "rtv.select_map"));
    ctx.row();

    // Scrollable pane constrained to safe mobile dimensions
    ctx.pane(MenuContext.SAFE_WIDTH, 260f, paneTable -> {
        if (entries.isEmpty()) {
            paneTable.add(UiBuilder.label("[gray]" + Tr.t(ctx.getSession(), "common.empty"))
                .wrap().align("left").growX()).row();
            return;
        }

        for (var entry : entries) {
            TableBuilder card = UiBuilder.table().align("left");

            // Left-aligned title with right-aligned stats
            card.add(UiBuilder.label(entry.name()).wrap().align("left").growX());
            card.add(UiBuilder.label("[gray](" + entry.votes() + ")").align("right").padLeft(4f));
            card.row();

            // Button matching standard row height (34px)
            String actionId = ctx.registerAction((sess, st, res) -> handleVote(sess, entry));
            card.add(UiBuilder.button("[accent]Vote")
                .clicked(actionId)
                .width(120f)
                .height(34f)
                .align("right")
                .colspan(2)
                .pad(2f));

            paneTable.add(card.growX().pad(2f)).row();
        }
    });
}
```

### Pattern B: Stack Overlay (Badge / Banner on Image)
```java
// Overlays a text badge on top of a map preview image
StackBuilder stack = UiBuilder.stack();
stack.add(UiBuilder.image("map-preview").size(280f, 120f));
stack.add(UiBuilder.label("[accent]ACTIVE").align("topRight").pad(6f));

ctx.add(stack);
ctx.row();
```

### Pattern C: Clickable Table Card (`buttonTable`)
```java
// Uses buttonTable so the entire card row is clickable
ButtonTableBuilder cardButton = UiBuilder.buttonTable().style("flatBorder");
cardButton.clicked(ctx.registerAction((sess, st, res) -> openDetails(sess, item)));
cardButton.add(UiBuilder.image(item.icon()).size(32f).padRight(6f));
cardButton.add(UiBuilder.label(item.title()).growX().align("left"));
cardButton.add(UiBuilder.label("[stat]" + item.cost()).align("right"));

ctx.add(cardButton.growX().pad(3f));
ctx.row();
```

### Pattern D: In-Place Partial Update
When updating a menu section (such as search results or pagination) without redrawing the entire dialog, assign an `id` to the target container and set `hideOnClick(false)`:
```java
// In Menu: Keep dialog visible on interaction
this.hideOnClick = false;

// Search field triggers 'search' on Enter
ctx.field(query, "Search...", q -> refreshList(ctx, q)).id("searchField");
ctx.row();

// Pane has a known ID for targeted updates
ctx.pane(MenuContext.SAFE_WIDTH, 240f, table -> populateRows(table, query)).id("resultPane");
```
