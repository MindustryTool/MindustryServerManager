# Agent Rules

- Always ask for the desired direction or approach before making significant architectural or implementation decisions.

- Always provide multiple possible solutions with their pros and cons when there is more than one reasonable approach.

- Always use short, concise sentences and common, easy-to-understand words.

- Do not duplicate functionality that already exists elsewhere in the project.

- Reuse existing utilities, abstractions, and APIs whenever possible.

- Do not add comments that merely restate what the code does. Comments should explain non-obvious decisions or constraints.

- Do not introduce side effects like falling back to a default when null unless asked.

- Do not silently swallow exceptions; must fail loud unless asked.

- Keep responsibilities separated.

- Avoid creating abstractions for one-off operations unless they provide clear value.

- Do not write meaningless tests. Tests must verify the behavior or flow of code.

- Do not introduce duplicated state; single source of truth is a must.

- Prefer dependency injection.

- Prefer builder pattern when having multiple constructor overloads with default values.

- Prefer state machine rather than multiple states.

- Always use imports instead of fully qualified class names in code. For example, use `import java.util.Objects;` and
  `Objects.requireNonNull(...)` instead of `java.util.Objects.requireNonNull(...)`.

- Code is written with test in mind but do not write only for testing code/function
