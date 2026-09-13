# Task 3 Report: KubeJS and JSON Recipe Contract Migration

## Changes

- Removed the KubeJS `level_requirements` schema key. `requiresLevel` now appends an internal `LevelRequirement` to `requirements`, encoded as `mmcr:level`.
- Kept the KubeJS builder on canonical internal requirements and added the public `RecipeRequirement` overload for `addRequirement`.
- Changed `KubeJSApi.levelRequirement` to return the public immutable level requirement after preserving registry/type validation.
- Removed JSON parsing support for `level_requirements`; it is now rejected with a structured error at `level_requirements`.
- Validated canonical level requirements at the `requirements` parser path before recipe construction.
- Migrated all level entries in `A_Level_Machine.js` to `requirements` entries using `type: 'mmcr:level'` and `io: 'input'`.

## TDD Evidence

- Red: ran the focused test command before production changes. Five assertions failed because the old schema key and JSON field were still accepted, the schema function wrote the old field, and the KubeJS API returned an internal requirement.
- Green: ran the same focused test command after implementation successfully.

## Verification

```text
gradlew test --no-daemon --tests cn.howxu.mmcr.compat.kubejs.MachineRecipeSchemaTest --tests cn.howxu.mmcr.api.recipe.MachineRecipeJsonTest --tests cn.howxu.mmcr.compat.kubejs.KubeJSApiTest
BUILD SUCCESSFUL

gradlew compileJava --no-daemon
BUILD SUCCESSFUL

gradlew test --no-daemon
BUILD SUCCESSFUL
```

## Self-Review

- Confirmed the old schema key is absent and no compatibility JSON reader remains.
- Confirmed `MachineRecipeJson` reports canonical level validation at `requirements` while the recipe constructor retains its independent invariant.
- Confirmed no sync code was changed; protocol remains v2 for Task 4 to migrate.
- Confirmed `git diff --check` reports no whitespace errors.

## Concerns

- GameTest was not run, per the task instruction. The focused and full unit test suites plus `compileJava` passed.
