MMCREvents.server(event => {
    const api = event.getAPI()
    const structure = event.createStructure("mmcr_kubejs:electric_blast_furnace")

    structure
        .pattern(['AAA', 'XXX', 'XXX', 'AAA'])
        .pattern(['AAA', 'X X', 'X X', 'AAA'])
        .pattern(['ABA', 'XXX', 'XXX', 'AAA'])
        .set('X', api.block("kubejs:machine_coil_cupronickel"))
        .set('A', api.anyOf(
            api.anyOfItemInput(),
            api.anyOfItemOutput(),
            api.anyOfEnergyInput(),
            api.block('kubejs:heat_proof_machine_casing')
        ))
        .controller('B')
        .build()
})
