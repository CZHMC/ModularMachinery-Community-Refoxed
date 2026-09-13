MMCREvents.startup(event => {
    event
        .createMachine("kubejs:hello_world")
        .recipePool("kubejs:hello_world")
        .displayNameKey("machine.kubejs.hello_world")
        .appearance("minecraft:bricks")
        .register()
})
