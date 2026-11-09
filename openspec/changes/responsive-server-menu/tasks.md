## 1. Image Fetching & Caching

- [ ] 1.1 Implement `ServerImageService` with an in-memory 5-minute cache for image bytes and 404 negative responses.
- [ ] 1.2 Implement texture streaming utility to send `net-server-{id}` textures to player connections via `Vars.netServer.sendTexture` on `Tasks.io`.
- [ ] 1.3 Write unit tests for `ServerImageService` testing cache hit, 5-minute expiration, and negative 404 handling.

## 2. Server List Menus Redesign

- [ ] 2.1 Update `ServerListMenu` to `fillScreen = true` with a responsive wrap grid (`UiBuilder.table().wrap(true)`), left-aligned vertical cards, `"nomap"` fallback, and left-aligned metadata.
- [ ] 2.2 Update `GlobalServerListMenu` to match the responsive wrap grid and left-aligned card structure with admin redirect actions.
- [ ] 2.3 Hook asynchronous texture streaming into `ServerListMenu.build` and `GlobalServerListMenu.build`.

## 3. Verification & Previews

- [ ] 3.1 Run plugin unit tests (`./gradlew :plugin:test`) to verify menu structure, actions, and caching logic.
- [ ] 3.2 Run menu preview renderer (`./gradlew :plugin:renderPreviews`) and verify snapshot images across desktop, tablet, and mobile viewports.
