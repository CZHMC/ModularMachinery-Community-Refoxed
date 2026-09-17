MMCREvents.startup(event => {

    const distillation_column = event
        .createMachine("mmcr_kubejs:distillation_column")
        .displayNameKey("machine.mmcr_kubejs.distillation_column")
        .recipePool("mmcr_kubejs:distillation_column")
        .appearance('minecraft:iron_block')
        .expandableStructure(true)

    distillation_column.register()
})
