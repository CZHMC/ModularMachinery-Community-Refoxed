MMCREvents.startup(event => {
    event
        .createMachine("kubejs:artificial_star_video")
        .recipePool("kubejs:artificial_star_video")
        .displayNameKey("machine.kubejs.artificial_star_video")
        .register()
})
