## 1. Annotation Updates

- [x] 1.1 Add `int version() default 1;` attribute to `plugin.annotations.Configuration` in the `annotation` module

## 2. Core Implementation in ConfigManager

- [x] 2.1 Pass `Configuration` metadata into `ConfigManager.load`
- [x] 2.2 Implement configuration saving with root `"version"` property and pretty-printed formatting
- [x] 2.3 Implement version checking logic to overwrite files when `fileVersion < codeVersion` (including unversioned files as `0`)
- [x] 2.4 Implement warning logging when `fileVersion > codeVersion` without overwriting the file

## 3. Verification and Testing

- [x] 3.1 Write unit tests for `ConfigManager` covering file creation, outdated overwrite, unversioned overwrite, matching version load, and newer version warning
- [x] 3.2 Run tests to verify all requirements pass
