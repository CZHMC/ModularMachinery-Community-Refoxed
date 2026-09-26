MMCREvents.client(event => {
    // registry additional information for recipe pool
    event.addRecipePoolInfo(
        "mmcr_kubejs:kubejs_blast_furnace",
        "jei.kubejs.blast_furnace.info_1"
    )

    // argument is supported
    event.addRecipePoolInfo(
        "mmcr_kubejs:kubejs_blast_furnace",
        "jei.kubejs.blast_furnace.info_2",
        10
    )

    // additional information for a specialized recipe id
    event.addRecipeInfo(
        "mmcr_kubejs:blast_furnace_1",
        "jei.kubejs.blast_furnace.info_3"
    )

    // argument is supported
    event.addRecipeInfo(
        "mmcr_kubejs:blast_furnace_1",
        "jei.kubejs.blast_furnace.info_4",
        "Character"
    )

})