package dev.pampa.mycloudgallery.feature.library.trash

// R18 (revisione di Fase 7): i test di questo modulo si fabbricano la prova del tocco da soli. Nel codice di produzione la prova nasce solo in un composable
// (`tapOnButton`, `SheetTap`, `DoubleTapConfirm`); i falsi pubblici in `src/main` che la davano a chiunque non ci sono piu' (`TrashTapGuardTest`).

/** La prova di [taps] tocchi, per i test del modulo. */
internal fun userTap(taps: Int = 1): UserTap = UserTap(taps)
