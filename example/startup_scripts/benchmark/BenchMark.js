MMCREvents.startup(event => {

    const m1 = event
        .createMachine("mmcr_kubejs:bench_1")
        .displayNameKey("machine.mmcr_kubejs.bench_1")
        .recipePool("mmcr_kubejs:bench_1")
        .appearance("minecraft:bricks")

    m1.register()

    const m2 = event
        .createMachine("mmcr_kubejs:bench_2")
        .displayNameKey("machine.mmcr_kubejs.bench_2")
        .recipePool("mmcr_kubejs:bench_1")
        .appearance("minecraft:iron_block")
        .allowMultithreading()
        .allowParallelism()
        .maxParallelAmount(2147483647)

    m2.register()
})
