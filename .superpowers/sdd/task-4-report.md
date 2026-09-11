## Status

Implemented and verified Task 4: AE2 stocking capabilities retain their resource/operation contract but are not projected as native item or fluid handlers without `TransferFacet`. The loaded AE2 bridge still registers only the stocking grid node host; `GENERIC_INTERNAL_INV` and `ME_STORAGE` remain absent for the stocking block entity type.

## Commit

- Code commit: `81195f9` (`fix: isolate AE2 stocking capability IO`)

## Modified Files

- `src/main/java/cn/howxu/mmcr/internal/event/ModCapabilities.java`
- `src/test/java/cn/howxu/mmcr/compat/appliedenergistics2/AE2InputInterfaceKindTest.java`

## TDD

- Red: `./gradlew test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2InputInterfaceKindTest` failed after the test fixture was corrected: `9 tests completed, 1 failed`, at the stocking native-handler assertion.
- Green: `./gradlew test --no-daemon --tests cn.howxu.mmcr.compat.appliedenergistics2.AE2InputInterfaceKindTest --tests cn.howxu.mmcr.internal.tile.IOPortCapabilityCreationTest` passed: `BUILD SUCCESSFUL`.
- Regression: `./gradlew test --no-daemon` passed: `BUILD SUCCESSFUL`.

## Changes

- Native item/fluid projection now skips hosted capabilities whose declared facet set lacks `TransferFacet`.
- The existing generic `resourceStorage` helper remains unchanged for ResourceFacet-only consumers such as AutoIO.
- Added assertions for stocking `ResourceFacet`/`OperationFacet`, absent native item/fluid handlers, and absent AE2 generic inventory/ME storage while retaining the grid node host.

## Concerns

- GameTest was not run per the task instruction.
- The full unit test suite passed; no other concerns were found.
