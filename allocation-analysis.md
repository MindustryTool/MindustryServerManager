# Object Allocation Deep Dive — GC Pressure Analysis

This document captures findings from exploring the plugin project for high-volume object allocations that could cause thousands of short-lived objects and slow down GC.

---

## Allocation Taxonomy

```
┌─────────────────────────────────────────────────────────────────┐
│  ALLOCATION SCALE                                               │
├─────────────────────────────────────────────────────────────────┤
│  per-tick / per-frame     →  thousands/sec (worst for GC)      │
│  per-message / per-event  →  hundreds/sec                       │
│  per-player-join          →  N objects once per player          │
│  per-request              →  bursty, depends on API traffic     │
│  startup / one-time       →  negligible                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Tier 1 — Critical: Per-Player Unbounded Growth

### SessionRepository.cache — Never Evicted

**File:** `plugin/src/main/java/plugin/session/SessionRepository.java:28`

```java
private final ConcurrentHashMap<String, SessionData> cache = new ConcurrentHashMap<>();
```

- Every player who ever joins creates a `SessionData` that stays in the cache forever.
- No TTL, no LRU, no max size.
- `SessionData` contains: `ArrayList<String> unlockedTrails`, `HashMap<String, Integer> dailyTaskProgress`
- Each cached `SessionData` = ~6-8 objects (ArrayList, HashMap, Strings, longs, etc.)
- **Growth:** O(unique_players_ever) — unbounded, linear forever.

### SessionService.data — Active Sessions

**File:** `plugin/src/main/java/plugin/session/SessionService.java:50`

```java
private final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();
```

- Created on every `PlayerJoin`, removed on `PlayerLeave` — bounded by concurrent players.
- Each `Session` allocates: `Locale`, `SessionData` ref, `Long joinedAt`, `Instant lastPotentialAfkTime`, `Instant lastActionTime`, `Locale` via `Locale.forLanguageTag(...)`.
- Per-player cost: ~10-15 objects at join time.

### SessionRepository.leaderBoard() — Per Call

**File:** `plugin/src/main/java/plugin/session/SessionRepository.java:161-184`

Called every 60 seconds via `SessionService.updateLeaderboardData()`.

```java
Seq<RankData> players = new Seq<>();          // 1 Seq
for (var row : rows) {
    var data = JsonUtils.readJsonAsClass(json, SessionData.class);  // 1 SessionData + JSON parsing objects
    players.add(new RankData(uuid, data, totalExp));  // 1 RankData per row
}
```

- Creates `SessionData` objects for every row in the top-10 leaderboard via JSON deserialization.
- Each `SessionData.copy()` also creates new `ArrayList` and `HashMap` copies (SessionData.java:41-43).

---

## Tier 2 — High: Per-Message / Per-Event Allocations

### Chat Translation — Every Chat Message

**File:** `plugin/src/main/java/plugin/chat/ChatTranslation.java`

Even when rate-limited or gateway-disconnected, the chat filter snapshot runs on **every** message:

```java
// Lines 59-70: Created on EVERY chat message
List<Player> blankLocalePlayers = new ArrayList<>();     // 1 ArrayList
Map<String, List<Player>> playersByLang = new HashMap<>();  // 1 HashMap + N ArrayLists per language
Groups.player.forEach(p -> {
    playersByLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(p);  // 1 ArrayList per new lang
});
```

- **Per message:** 1 ArrayList + 1 HashMap + 1 ArrayList per distinct language among online players.
- With 50 players speaking 5 languages: ~7 collections per message.
- If 100 messages/minute: ~700 collections/minute, plus wrapper objects.

Then for each language group that needs translation:

```java
// Line 115: Per translation request
new TranslationRequest(cleanText, lang)   // 1 object (2 Strings)

// Line 123: Per successful translation
new TranslationResult(translatedText, sourceLanguage)  // 1 record object (2 Strings)
```

- Translation is async, but objects are still short-lived (Gen-0 GC pressure).

### Chat `formatMessage()` — String Allocation Storm

**File:** `plugin/src/main/java/plugin/chat/ChatTranslation.java:150-168`

```java
String baseMessage = senderName + "[white]: " + originalMessage;  // String concat = StringBuilder + String
return baseMessage + " [#00d47e](" + result.translatedText() + ")";  // Another concat
```

- Every translated message creates 2-3 intermediate StringBuilder/String objects.
- Multiplied by recipients: `recipient.sendMessage(formatted, ...)` sends to each player in the language group.
- With 10 recipients per message: 10 formatted strings per translation.

---

## Tier 3 — Medium-High: Periodic Bulk Allocation

### HubService.renderServerLabels() — Every 15 Seconds

**File:** `plugin/src/main/java/plugin/hub/HubService.java:343-423`

```java
// MapObjective case (every 15s)
MapObjectives objectives = new MapObjectives();      // 1
FlagObjective flagObjective = new FlagObjective();   // 1
Seq<TextMarker> markers = new Seq<>();               // 1
for (var core : serverCores) {
    markers.add(createServerMarker(core));           // 1 TextMarker per core
}
TextMarker[] markerArray = new TextMarker[markers.size];  // 1 array
flagObjective.markers(markerArray);
objectives.add(flagObjective);
Call.setObjectives(objectives);
```

- Per 15s cycle: `MapObjectives` + `FlagObjective` + `Seq` + `TextMarker[]` + N `TextMarker` objects.
- With 20 cores: ~24 objects per cycle → ~96/minute.
- Each `TextMarker` creation goes through `createServerMarker()` → `entry.renderLabel()` → `HubService.newLine()` → `String.split(" ")` + `StringBuilder` → more objects.

### HubService.refreshServerList() — Every 60 Seconds

**File:** `plugin/src/main/java/plugin/hub/HubService.java:300-341`

```java
List<HubEntry> entries = new ArrayList<>();           // 1
for (var server : servers) {
    entries.add(new ServerHubEntry(server));          // 1 per server
}
for (var room : playerConnectService.getActiveRooms()) {
    entries.add(new PlayerConnectHubEntry(room));     // 1 per room
}
```

- Per 60s cycle: 1 ArrayList + N HubEntry objects (servers + rooms).
- Bounded by server count, but still allocates every cycle.

### ServerHubEntry.renderLabel() — Per Label Render

**File:** `plugin/src/main/java/plugin/hub/ServerHubEntry.java:34-53`

```java
var mods = new ArrayList<>(server.getMods());  // 1 ArrayList copy per render
mods.removeIf(...);
String message = ... + HubService.newLine(name) + ... + HubService.newLine(description) + ...;
```

- `newLine()` creates `String[] word = text.split(" ")` + `StringBuilder` per call.
- Called twice per label (name + description) + once for mapName.
- Per label render: ~3-5 String[] + StringBuilder objects.

---

## Tier 4 — Burst: API Gateway Responses

### ApiGateway.getJson() — Per RPC Call

**File:** `plugin/src/main/java/plugin/gateway/ApiGateway.java:215-293`

```java
HashMap<String, Object> data = new HashMap<>();           // 1
HashMap<String, Object> gameStats = new HashMap<>();      // 1 (9 puts)
ArrayList<HashMap<String, String>> maps = new ArrayList<>();  // 1 + N HashMaps
// Per map:
HashMap<String, String> tags = new HashMap<>();           // 1 per map
HashMap<String, Object> info = new HashMap<>();           // 1 per map
// Per thread:
HashMap<String, Object> info = new HashMap<>();           // 1 per thread
Arrays.asList(thread.getStackTrace())                     // StackTraceElement[] per thread
.map(stack -> stack.toString())                           // 1 String per stack frame
.collect(Collectors.toList());                            // 1 ArrayList per thread
HashMap<String, Object> settings = new HashMap<>();       // 1 + K puts
```

- **Per call:** 4-5 top-level maps + M map-info maps + T thread-info maps + stack trace strings.
- If server has 50 maps and 100 threads: ~150+ HashMaps + thousands of strings per call.
- Called on: every `get-json` RPC, every session create/remove, every state change, every play event.
- **This is the biggest single burst allocator in the system.**

### ApiGateway.getPlayersInfo() — Per Player List Request

**File:** `plugin/src/main/java/plugin/gateway/ApiGateway.java:425-477`

```java
List<Predicate<PlayerInfo>> conditions = new ArrayList<>();  // 1 + N predicates
List<PlayerInfo> filtered = bans.list().stream()...collect(Collectors.toList());  // 1 ArrayList
List<PlayerRecord> data = filtered.stream()...map(ban -> new PlayerRecord()...).collect(Collectors.toList());  // 1 PlayerRecord per player
return new PlayerRecordPage(...);  // 1
```

- Creates `PlayerRecord` objects for every player in the filtered list.
- Bounded by page size, but still allocates per request.

---

## Tier 5 — Menu/UI Rendering (Per-Player, Per-Menu-Open)

### ServerMenu.send() — New Menu Object Per Player

**File:** `plugin/src/main/java/plugin/menus/ServerMenu.java:41`

```java
ServerMenu<T> copy = Registry.inject(this.getClass());  // New menu instance per send
```

- Every time a menu is sent to a player, a new menu object is created via DI injection.
- If 50 players each open 5 menus: 250 menu objects.

### MenuContext — Per Menu Build

**File:** `plugin/src/main/java/plugin/menus/MenuContext.java:34-36`

```java
private final TableBuilder root = UiBuilder.table();    // 1 builder
private final Map<String, MenuAction<T>> actions = new HashMap<>();  // 1 HashMap
private final AtomicInteger actionCounter = new AtomicInteger();  // 1
```

- Each menu build creates: MenuContext, TableBuilder root, HashMap for actions, AtomicInteger, plus every UI element builder (LabelBuilder, ButtonBuilder, etc.).
- Each `registerAction()` creates `"act_" + counter` String.

### ActiveMenuEntry — Per Active Menu

**File:** `plugin/src/main/java/plugin/menus/PluginMenuService.java:194-206`

```java
private static class ActiveMenuEntry {
    final Session session;
    final Object state;
    final long token;
    final Instant createdAt = Instant.now();  // 1 Instant per entry
    final Map<String, MenuAction<Object>> actions = new ConcurrentHashMap<>();  // 1 map
}
```

- One per active menu per player.

---

## Tier 6 — Scheduled Task Wrappers (Per Schedule)

### Scheduler.createWrapper / createInvocationTask

**File:** `plugin/src/main/java/plugin/core/Scheduler.java:118-143`

Every `@Schedule` method creates `Runnable` wrapper objects. With main-thread `Timer.schedule()`:

```java
Timer.schedule(new Timer.Task() {  // 1 anonymous Timer.Task per scheduled method
    @Override
    public void run() {
        wrapped.run();
        Timer.schedule(this, intervalSeconds);  // Reschedules itself
    }
}, delaySeconds);
```

- Count = number of `@Schedule` methods × reschedules.
- Currently ~20+ scheduled methods across the codebase.
- Low per-object cost but constant churn.

---

## Summary Heatmap

| Area | Frequency | Objects per Event | Scale Factor | GC Risk |
|------|-----------|-------------------|--------------|---------|
| SessionRepository.cache | Per player join (forever) | ~6-8 | Unique players ever | 🔴 Unbounded growth |
| Chat translation snapshot | Per chat message | ~7-10 | Messages/min × langs | 🔴 High frequency |
| Chat TranslationRequest/Result | Per translated message | ~2 | Translations/min | 🟡 Medium |
| Hub renderServerLabels | Every 15s | ~24 | Cores × cycles | 🟡 Periodic |
| Hub refreshServerList | Every 60s | ~N+1 | Servers × cycles | 🟢 Low |
| ApiGateway.getJson | Per RPC call | ~150+ | Calls × maps × threads | 🔴 Burst heavy |
| Menu rendering | Per menu open per player | ~20+ | Players × menus | 🟡 Moderate |
| Scheduler wrappers | Per schedule tick | ~1-2 | Schedules × ticks | 🟢 Low |

---

## Key Observations

1. **SessionRepository.cache is the biggest long-term leak risk.** It grows with every unique player who ever joins and never evicts. With `SessionData` containing `ArrayList` and `HashMap`, this is not just references — it's real heap retention.

2. **Chat translation creates collections on every message, even when rate-limited.** The `ArrayList` + `HashMap` snapshot happens before the rate-limit check.

3. **ApiGateway.getJson() is the biggest single burst.** It serializes thread stack traces (potentially thousands of `StackTraceElement.toString()` strings) and full server state on every call.

4. **FloodGamemode and ZigerGamemode are actually well-optimized.** FloodGamemode explicitly reuses `unsuppressedCoresScratch` Seq. ZigerGamemode caches `FillInfo` per Block type. These are good patterns to emulate elsewhere.

5. **HubService.renderServerLabels() in MapObjective mode allocates heavily every 15s** — `MapObjectives`, `FlagObjective`, `TextMarker[]`, and per-core `TextMarker` + `StringBuilder` objects.

---

## Questions to Consider

- What is the typical concurrent player count? (Determines Session scale)
- How many chat messages per minute? (Determines translation allocation rate)
- How often is `get-json` called? (Determines API burst frequency)
- Are menus opened frequently during gameplay? (Determines UI allocation rate)
- Is `SessionRepository.cache` ever expected to handle players who never return?
