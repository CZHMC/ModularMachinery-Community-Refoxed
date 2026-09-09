MMCREvents.server(event => {
    const api = event.getAPI()

    event
        .createStructure("mmcr_kubejs:kubejs_blast_furnace")

        .pattern("  KKK  ", " CXXXC ", " CXXXC ", " CXXXC ", "  CCC  ")
        .pattern(" CXXXC ", "C     C", "C     C", "C     C", " CXXXC ")
        .pattern("KXXXXXK", "X     X", "X     X", "X     X", "CXXXXXC")
        .pattern("KXXXXXK", "X     X", "X     X", "X     X", "CXXXXXC")
        .pattern("KXXXXXK", "X     X", "X     X", "X     X", "CXXXXXC")
        .pattern(" CXXXC ", "C     C", "C     C", "C     C", " CXXXC ")
        .pattern("  KQK  ", " CXXXC ", " CXXXC ", " CXXXC ", "  CCC  ")
        .set('C', api.block('mekanism:sps_casing'))
        .set('X', api.block('mekanism:structural_glass'))
        .set('K', api.anyOf(
            api.block('mekanism:sps_casing'),
            api.ports()
        ))
        .controller('Q')

        .build()
})