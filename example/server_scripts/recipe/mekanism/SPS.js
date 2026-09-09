ServerEvents.recipes(event => {

    // raditional
    event.custom({
        type: 'mmcr:machine_recipe',
        machine: 'mmcr_kubejs:kubejs_sps',
        tick_time: 300,
        requirements: [
            {
                type: 'mekanism:chemical',
                io: 'input',
                kind: 'chemical',
                id: 'mekanism:nuclear_waste',
                amount: 1000,
            },
            {
                type: 'mekanism:chemical',
                io: 'output',
                kind: 'chemical',
                id: 'mekanism:spent_nuclear_waste',
                amount: 10,
            }
        ]
    })

    // normal
    event.custom({
        type: 'mmcr:machine_recipe',
        machine: 'mmcr_kubejs:kubejs_sps',
        tick_time: 300,
        requirements: [
            {
                type: 'mekanism:chemical',
                io: 'input',
                kind: 'chemical',
                id: 'mekanism:osmium',
                amount: 1000,
            },
            {
                type: 'mekanism:chemical',
                io: 'output',
                kind: 'chemical',
                id: 'mekanism:polonium',
                amount: 10,
            }
        ]
    })

    // normal plus 2
    event.custom({
        type: 'mmcr:machine_recipe',
        machine: 'mmcr_kubejs:kubejs_sps',
        tick_time: 300,
        requirements: [
            {
                type: 'mekanism:chemical',
                io: 'input',
                kind: 'chemical',
                id: 'mekanism:osmium',
                amount: 1000,
            },
            {
                type: 'mekanism:chemical',
                io: 'output',
                kind: 'chemical',
                id: 'mekanism:polonium',
                amount: 10,
            },
            {
                type: 'mekanism:chemical',
                io: 'output',
                kind: 'chemical',
                id: 'mekanism:antimatter',
                amount: 10,
            }
        ]
    })

    // heat
    event.custom({
        type: 'mmcr:machine_recipe',
        machine: 'mmcr_kubejs:kubejs_sps',
        tick_time: 300,
        requirements: [
            {
                type: 'mekanism:temperature',
                io: 'input',
                value: 450
            },
            {
                type: 'mekanism:chemical',
                io: 'input',
                kind: 'chemical',
                id: 'mekanism:osmium',
                amount: 1000,
            },
            {
                type: 'mekanism:chemical',
                io: 'output',
                kind: 'chemical',
                id: 'mekanism:hydrogen',
                amount: 200,
                chance: 0.5
            },
            {
                type: 'mekanism:heat',
                io: 'output',
                value: 1200
            }
        ]
    })

})