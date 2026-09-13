MMCREvents.startup(event => {

    const builder = event
        .createMachine("mmcr_kubejs:kubejs_sps")
        .displayNameKey("machine.mmcr_kubejs.kubejs_sps")
        .recipePool("mmcr_kubejs:kubejs_sps")
        .appearance("mekanism:sps_casing")

    builder.register()
})
