## 1. Baseline

- [x] 1.1 Capture current `:plugin:dependencies` and `:server:dependencies` output for ref
- [x] 1.2 Zip-list current `plugin.jar` and `application.jar` as old baseline
- [x] 1.3 Note VS Code repro: edit `common` type, check `plugin` error without clean

## 2. Plugin thin and fat split

- [x] 2.1 Restore default `jar` to thin in `plugin/build.gradle.kts`
- [x] 2.2 Move fat merge to separate pack task keeping `plugin.jar` name and `plugin.json`
- [x] 2.3 Keep sqlite native strips and `plugin/processor` plus service excludes in fat task only
- [x] 2.4 Wire `assemble` and `build` to still produce `plugin/build/libs/plugin.jar`

## 3. Server thin restore

- [x] 3.1 Remove `jar.enabled=false` in `server/build.gradle.kts`
- [x] 3.2 Keep `shadowJar` as `application.jar` for deploy
- [x] 3.3 Wire `build` to still produce `server/build/libs/application.jar`

## 4. Toolchain unify

- [x] 4.1 Switch `common`, `database`, `annotation`, `plugin` to toolchain 17
- [x] 4.2 Verify `./gradlew :server:build :plugin:build` passes with no toolchain drift

## 5. Verify

- [x] 5.1 Zip-list diff new vs old jars for class list match
- [x] 5.2 Run `./gradlew :plugin:test :server:test` with `ComponentRegistry` check
- [x] 5.3 Reload Gradle in VS Code and redo edit test with no clean
- [x] 5.4 Confirm `Dockerfile` and `Dockerfile.local` outputs still copy
