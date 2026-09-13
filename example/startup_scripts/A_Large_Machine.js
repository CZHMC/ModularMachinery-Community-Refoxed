MMCREvents.startup(event => {
    const builder = event
        .createMachine("mmcr_kubejs:gzr")
        .displayNameKey("machine.mmcr_kubejs.gzr")
        .recipePool("mmcr_kubejs:gzr");

    builder.register()
})
