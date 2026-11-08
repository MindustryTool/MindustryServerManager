## Why

`ActionFilterManager` registers filters on Mindustry's `Administration.addActionFilter` which executes on every single player action (building, breaking, tapping, configuring). The current implementation inspects method parameters, allocates a new `Object[]` array, queries `Registry.get(...)`, and calls reflection `method.invoke(...)` on every action event. This creates significant garbage collection churn and execution overhead during intense building.

## What Changes

- Pre-resolve method parameter bindings and `Registry` dependencies once at filter registration time.
- Use a pre-bound invoker to dispatch player actions without allocating object arrays or inspecting reflection parameters on the hot path.

## Capabilities

### New Capabilities
- `action-filters`: Fast dispatching and invocation of player action filters without per-action reflection allocations.

### Modified Capabilities
None.

## Impact

- `plugin.core.ActionFilterManager`: Build and store pre-resolved invokers when filters are registered.
