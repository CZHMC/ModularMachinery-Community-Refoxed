ServerEvents.recipes(event => {
    event.custom({
        type: 'mmcr:machine_recipe',
        recipe_pool: 'kubejs:hello_world',
        tick_time: 100,
        requirements: [
            {
                type: 'minecraft:item',
                io: 'input',
                item: 'minecraft:rotten_flesh',
                count: 1
            },
            {
                type: 'minecraft:item',
                io: 'output',
                stack: {
                    id: 'minecraft:leather',
                    count: 1
                }
            },
            {
                type: 'neoforge:energy',
                io: 'input',
                fe_per_tick: 10
            }
        ]
    })
})
