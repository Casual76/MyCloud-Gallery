package dev.pampa.mycloudgallery.feature.map

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask

/** La maschera che non nasconde niente: per le prove "senza il filtro finale" (`FinalFilter` non ammette l'assenza di una maschera, R3 del 31/10). */
internal val NothingHidden: HiddenMask = HiddenMask { emptySet() }
