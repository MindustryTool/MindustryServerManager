## Context

`@ConditionOn` is a runtime-retained annotation whose `value()` is a `Class<? extends Condition>`; `Condition.check()` returns a boolean. Today `@ConditionOn` has `@Target(TYPE)` and is only evaluated once, in `Registry.init(...)`, to skip instantiating whole `@Component` classes when a condition fails (e.g. `Cfg.OnOfficial`, `Cfg.OnHub`).

Method-level handler annotations (`@ClientCommand`, `@Listener`, `@PlayerActionFilter`, `@ServerCommand`, `@Schedule`, `@Trigger`) are all processed in a single place: `Registry.initialize(Object)`, which iterates `clazz.getDeclaredMethods()` and dispatches each method to the appropriate manager/handler via `withAnnotation(...)`.

## Goals / Non-Goals

**Goals:**
- Allow `@ConditionOn` to be declared on methods in addition to classes.
- When a method carrying one of the six handler annotations also carries `@ConditionOn`, skip that registration during component initialization if `Condition.check()` returns `false`.
- Keep existing class-level behavior byte-for-byte equivalent.
- Reuse one condition-evaluation code path for class-level and method-level checks.

**Non-Goals:**
- No support for conditions on `@Init`, `@Destroy`, `@FileWatcher`, `@Configuration`, or `@Persistence` (not requested).
- No dynamic re-evaluation at runtime (conditions are evaluated once at registration time, same as today).
- No support for composing multiple `@ConditionOn` annotations on a single method.
- No changes to how commands/listeners execute after registration.

## Decisions

### 1. Widen `@ConditionOn` target to `{TYPE, METHOD}`

Change `@Target(ElementType.TYPE)` to `@Target({ElementType.TYPE, ElementType.METHOD})`. This preserves class-level usage and adds method-level placement. `ANNOTATION_TYPE` is intentionally excluded (meta-annotation use is out of scope).

### 2. Centralize condition evaluation in a `ConditionUtils` helper

Create `plugin/core/ConditionUtils.java` (or `plugin/annotations/ConditionUtils.java`) exposing:

```java
public static boolean passes(Method method) // true when no @ConditionOn or the condition checks true
public static boolean passes(Class<?> type) // true when no @ConditionOn or the condition checks true
```

Each method instantiates the condition class via its no-arg constructor (mirroring the existing code in `Registry.init`) and returns `condition.check()`. On failure to instantiate/check, throw a `RuntimeException` with the target name, matching current error behavior.

Rationale: the class-level check in `Registry.init` and the new method-level checks share identical semantics; a single helper avoids divergent behavior.

### 3. Gate the six handler dispatches in `Registry.initialize`

Inside the method loop in `initialize`, guard each of the six handler registrations with `ConditionUtils.passes(method)`:

```java
withAnnotation(method, Schedule.class, a -> {
    if (ConditionUtils.passes(method)) get(Scheduler.class).process(a, instance, method);
});
// ... same guard for Listener, Trigger, ClientCommand, ServerCommand, PlayerActionFilter
```

Alternatives considered:
- **Guard at the top of the method loop (`if (!passes) continue;`)** — simpler, but would also suppress `@Init` and `@FileWatcher` on the same method, which is outside the requested scope and could silently break existing behavior.
- **Guard inside each handler (EventRegistrar, Scheduler, etc.)** — spreads condition logic across six classes and couples managers to annotation evaluation; less cohesive. Rejected in favor of keeping the gate in the single dispatch point (`Registry`).
- **Check inside `Condition` passed to managers at registration** — over-engineered; conditions should be evaluated at registration time only.

Guarding each dispatch individually keeps `@Init`/`@FileWatcher` unaffected and precisely matches the six annotations requested.

### 4. Refactor the class-level check in `Registry.init` to use `ConditionUtils`

Replace the inline `ConditionOn`/`Condition` logic in `Registry.init` (lines 56-70) with `ConditionUtils.passes(clazz)`, returning early (skip component) when it is `false`. Behavior is identical; it only consolidates the code path.

## Risks / Trade-offs

- [A method carrying both a gated handler annotation and `@Init`/`@FileWatcher` would register the latter even if the condition fails] → Intended: only the six requested handler annotations are gated; documented as a deliberate scope boundary.
- [Conditions are evaluated once at component initialization, not per invocation] → Matches existing class-level semantics; documented so users don't expect live re-evaluation.
- [Throwing `RuntimeException` on a broken condition class will fail component initialization] → Same as current class-level behavior; keeps failures loud rather than silently disabling handlers.
- [Command/action-filter registrations may already be registered with an external `CommandHandler` at the time `initialize` runs] → Registration is skipped entirely, so nothing needs un-registering; no change to handler lifecycle.

## Migration Plan

No migration required. Existing components and class-level `@ConditionOn` usages remain valid; the annotation change is source- and binary-compatible.

## Open Questions

None.