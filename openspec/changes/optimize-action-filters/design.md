## Context

Mindustry players generate dozens to hundreds of action events per second when constructing and deconstructing blocks. `ActionFilterManager.addFilter` currently binds a lambda that inspects `method.getParameters()` on every single action.

## Goals / Non-Goals

**Goals:**
- Eliminate per-action array allocations and dynamic reflection queries in `ActionFilterManager`.
- Maintain full compatibility with `@PlayerActionFilter` signatures accepting `PlayerAction` and injected services.

**Non-Goals:**
- Changing action filtering rules or security permissions.

## Decisions

### 1. Pre-Resolved Invoker Wrapper
- At registration time, parse `method.getParameters()` once.
- Identify the index of `PlayerAction.class` and pre-resolve all other dependency arguments via `Registry.get(param.getType())`.
- Construct a pre-bound invoker holding the fixed argument template. On each action, copy the template, set the action parameter at its known index, and invoke the method.
- *Rationale*: Avoids repeated `method.getParameters()` allocation and repeated `Registry.get(...)` lookups on every single block action.

## Risks / Trade-offs

- **[Service state mutability]** → `Registry` singletons are instantiated at startup and remain valid for the plugin lifecycle.
